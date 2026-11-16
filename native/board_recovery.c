#include "board_recovery.h"
#include "common/homography.h"
#include "common/matd.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

enum { SIDE = 6, TAGS = SIDE * SIDE, BITS = 36 };

static int col(int id) { return id % SIDE; }
static int row(int id) { return SIDE - 1 - id / SIDE; }

static int compare_double(const void *left, const void *right) {
    double a = *(const double *)left, b = *(const double *)right;
    return (a > b) - (a < b);
}

static matd_t *fit_board(apriltag_detection_t *seeds[TAGS], int count, double *half) {
    zarray_t *points = zarray_create(sizeof(float[4]));
    int min_row = SIDE, max_row = -1, min_col = SIDE, max_col = -1;
    for (int id = 0; id < TAGS; ++id) {
        if (!seeds[id]) continue;
        int x = col(id), y = row(id);
        if (x < min_col) min_col = x;
        if (x > max_col) max_col = x;
        if (y < min_row) min_row = y;
        if (y > max_row) max_row = y;
        float point[] = {x, y, seeds[id]->c[0], seeds[id]->c[1]};
        zarray_add(points, point);
    }
    matd_t *board = NULL;
    if (count >= 4 && max_row - min_row >= 2 && max_col - min_col >= 2) {
        // Reject collinear seeds before fitting a projective map.
        double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
        for (int id = 0; id < TAGS; ++id) if (seeds[id]) {
            int x = col(id), y = row(id);
            sx += x; sy += y; sxx += x*x; syy += y*y; sxy += x*y;
        }
        double vx = count*sxx-sx*sx, vy = count*syy-sy*sy, vxy = count*sxy-sx*sy;
        if (vx*vy - vxy*vxy > 0.05*vx*vy) board = homography_compute(points, HOMOGRAPHY_COMPUTE_FLAG_SVD);
    }
    zarray_destroy(points);
    if (!board) return NULL;
    matd_t *inverse = matd_inverse(board);
    if (!inverse) { matd_destroy(board); return NULL; }

    double widths[TAGS * 4];
    int nwidths = 0;
    bool valid = true;
    for (int id = 0; id < TAGS && valid; ++id) {
        apriltag_detection_t *tag = seeds[id];
        if (!tag) continue;
        double x, y;
        homography_project(inverse, tag->c[0], tag->c[1], &x, &y);
        valid = isfinite(x) && isfinite(y) && hypot(x-col(id), y-row(id)) < .14;
        for (int i = 0; i < 4 && valid; ++i) {
            homography_project(inverse, tag->p[i][0], tag->p[i][1], &x, &y);
            double w = (fabs(x-col(id)) + fabs(y-row(id))) * .5;
            valid = isfinite(w) && w >= .22 && w <= .48;
            if (valid) widths[nwidths++] = w;
        }
    }
    matd_destroy(inverse);
    if (!valid || !nwidths) { matd_destroy(board); return NULL; }
    qsort(widths, nwidths, sizeof(double), compare_double);
    *half = widths[nwidths / 2];
    return board;
}

static uint8_t sample(const image_u8_t *image, double x, double y) {
    if (!(x >= 0 && y >= 0 && x < image->width - 1 && y < image->height - 1)) return 255;
    int ix = (int)x, iy = (int)y;
    double fx = x - ix, fy = y - iy;
    const uint8_t *p = image->buf + iy * image->stride + ix;
    return (1 - fy) * ((1 - fx) * p[0] + fx * p[1])
         + fy * ((1 - fx) * p[image->stride] + fx * p[image->stride + 1]) + .5;
}

static bool inside(const image_u8_t *image, double x, double y) {
    return x >= 0 && y >= 0 && x < image->width && y < image->height;
}

static double grid_sample(const image_u8_t *image, const matd_t *board, double x, double y) {
    double px, py;
    homography_project(board, x, y, &px, &py);
    return sample(image, px, py);
}

static uint64_t rotate_code(uint64_t code) {
    return ((code << 9) | (code >> 27)) & ((UINT64_C(1) << BITS) - 1);
}

struct cell_decode {
    double x, y, half;
    int rotation, errors;
    float contrast;
};

static void read_cell(const image_u8_t *image, const matd_t *board, const apriltag_family_t *family,
                      int id, double x, double y, double half, struct cell_decode *best) {
    double black = 0, white = 0;
    const double unit = half / 4;
    for (int k = -2; k <= 2; ++k) {
        double a = k * unit;
        double b = half - unit * .5, w = half + unit * .55;
        black += grid_sample(image, board, x+a, y-b) + grid_sample(image, board, x+a, y+b)
               + grid_sample(image, board, x-b, y+a) + grid_sample(image, board, x+b, y+a);
        white += grid_sample(image, board, x+a, y-w) + grid_sample(image, board, x+a, y+w)
               + grid_sample(image, board, x-w, y+a) + grid_sample(image, board, x+w, y+a);
    }
    black /= 20;
    white /= 20;
    if (white - black < 45) return;

    double levels[BITS];
    for (int i = 0; i < BITS; ++i) {
        double bx = x - half + (family->bit_x[i] + .5) * unit;
        double by = y - half + (family->bit_y[i] + .5) * unit;
        levels[i] = grid_sample(image, board, bx, by);
    }
    const double thresholds[] = {.5, .42, .58, .34, .66, .26, .74};
    for (size_t t = 0; t < sizeof(thresholds)/sizeof(*thresholds); ++t) {
        double threshold = black + (white - black) * thresholds[t];
        uint64_t observed = 0;
        for (int i = 0; i < BITS; ++i) observed = (observed << 1) | (levels[i] > threshold);
        for (int rotation = 0; rotation < 4; ++rotation) {
            int errors = __builtin_popcountll(observed ^ family->codes[id]);
            // tag36h11 has minimum Hamming distance 11, so a code within
            // 2 bits of this ID can't also be within 2 bits of another ID.
            if (errors < best->errors) {
                *best = (struct cell_decode){x, y, half, rotation, errors, white - black};
            }
            observed = rotate_code(observed);
        }
        if (best->errors == 0) return;
    }
}

static void recover_legacy_board(image_u8_t *image, zarray_t *detections) {
    apriltag_detection_t *seeds[TAGS] = {0};
    int count = 0;
    apriltag_family_t *family = NULL;
    for (int i = 0; i < zarray_size(detections); ++i) {
        apriltag_detection_t *tag;
        zarray_get(detections, i, &tag);
        if (tag->id < 0 || tag->id >= TAGS) continue;
        if (seeds[tag->id]) return;
        seeds[tag->id] = tag;
        family = tag->family;
        ++count;
    }
    if (count < 4 || count >= TAGS || !family || strcmp(family->name, "tag36h11") != 0) return;
    double half;
    matd_t *board = fit_board(seeds, count, &half);
    if (!board) return;

    for (int id = 0; id < TAGS; ++id) {
        if (seeds[id]) continue;
        int gx = col(id), gy = row(id);
        bool visible = true;
        for (int i = 0; i < 4; ++i) {
            double x, y;
            homography_project(board, gx + (i & 1 ? half : -half),
                               gy + (i & 2 ? half : -half), &x, &y);
            visible = visible && inside(image, x, y);
        }
        if (!visible) continue;

        struct cell_decode best = {.errors = 3};
        read_cell(image, board, family, id, gx, gy, half, &best);
        const double shifts[] = {0, -.027, .027, -.054, .054};
        for (int a = 0; a < 3 && best.errors > 0; ++a) {
            double h = half + (a == 1 ? -.025 : a == 2 ? .025 : 0);
            for (int y = 0; y < 5 && best.errors > 0; ++y) {
                for (int x = 0; x < 5 && best.errors > 0; ++x) {
                    if (a == 0 && x == 0 && y == 0) continue;
                    read_cell(image, board, family, id, gx+shifts[x], gy+shifts[y], h, &best);
                }
            }
        }
        if (best.errors > 2) continue;

        apriltag_detection_t *tag = calloc(1, sizeof(*tag));
        if (!tag) continue;
        tag->id = id;
        tag->family = family;
        tag->hamming = best.errors;
        tag->decision_margin = best.contrast * .5f;
        double angle = best.rotation * M_PI / 2, c = cos(angle), s = sin(angle);
        double local[] = {best.half*c, -best.half*s, best.x, best.half*s, best.half*c, best.y, 0, 0, 1};
        matd_t *map = matd_create_data(3, 3, local);
        tag->H = matd_multiply(board, map);
        matd_destroy(map);
        homography_project(tag->H, 0, 0, &tag->c[0], &tag->c[1]);
        for (int i = 0; i < 4; ++i) {
            homography_project(tag->H, i == 1 || i == 2 ? 1 : -1, i < 2 ? 1 : -1,
                               &tag->p[i][0], &tag->p[i][1]);
        }
        zarray_add(detections, &tag);
    }
    matd_destroy(board);
}

static double shortest_side_squared(const apriltag_detection_t *tag) {
    double minimum = INFINITY;
    for (int i = 0; i < 4; ++i) {
        int next = (i + 1) % 4;
        double dx = tag->p[i][0] - tag->p[next][0];
        double dy = tag->p[i][1] - tag->p[next][1];
        minimum = fmin(minimum, dx*dx + dy*dy);
    }
    return minimum;
}

static bool same_location(const apriltag_detection_t *first, const apriltag_detection_t *second,
                          double candidate_radius_squared) {
    if (first->id != second->id || first->family != second->family) return false;
    double dx = first->c[0] - second->c[0], dy = first->c[1] - second->c[1];
    double distance_squared = dx*dx + dy*dy;
    return distance_squared < candidate_radius_squared &&
           distance_squared < .1225 * shortest_side_squared(second);
}

zarray_t *taglens_detect(apriltag_detector_t *detector, image_u8_t *image, bool dense) {
    zarray_t *found = apriltag_detector_detect(detector, image);
    if (!dense) return found;

    int original_deglitch = detector->qtp.deglitch;
    detector->qtp.deglitch = 2;
    zarray_t *second = apriltag_detector_detect(detector, image);
    detector->qtp.deglitch = original_deglitch;
    for (int i = 0; i < zarray_size(second); ++i) {
        apriltag_detection_t *candidate;
        zarray_get(second, i, &candidate);
        bool duplicate = false;
        double radius_squared = .1225 * shortest_side_squared(candidate);
        for (int j = 0; j < zarray_size(found); ++j) {
            apriltag_detection_t *existing;
            zarray_get(found, j, &existing);
            if (same_location(candidate, existing, radius_squared)) {
                duplicate = true;
                break;
            }
        }
        if (!duplicate) {
            zarray_add(found, &candidate);
            candidate = NULL; // found now owns this detection.
            zarray_set(second, i, &candidate, NULL);
        }
    }
    apriltag_detections_destroy(second);
    recover_legacy_board(image, found);
    return found;
}
