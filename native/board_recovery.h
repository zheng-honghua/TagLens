#pragma once
#include "apriltag.h"

// Run ordinary detection and, for tag36h11 calibration boards, a second
// segmentation pass that separates tags from touching checker intersections.
zarray_t *taglens_detect(apriltag_detector_t *detector, image_u8_t *image, bool dense);
