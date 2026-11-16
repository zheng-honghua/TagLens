package com.taglens.scanner;

import android.graphics.Bitmap;
import java.util.*;
import org.json.*;
import org.opencv.android.Utils;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

final class Calibration {
    static final String[] MODES = {"AprilTag / ArUco 识别", "棋盘格标定", "AprilTag 标定板标定"};
    static final class Board {
        final int mode, rows, cols, first;
        final double size, gap;
        final boolean bottomFirst, hybrid, legacy;
        final double markerSize;
        Board(int mode, int rows, int cols, int first, double size, double gap, boolean bottomFirst,
              boolean hybrid, double markerSize, boolean legacy) {
            this.mode=mode; this.rows=rows; this.cols=cols; this.first=first;
            this.size=size; this.gap=gap; this.bottomFirst=bottomFirst;
            this.hybrid=hybrid; this.markerSize=markerSize; this.legacy=legacy;
        }
    }
    static final class Sample {
        final int w, h;
        final Point3[] world;
        final Point[] image;
        Sample(int w, int h, Point3[] world, Point[] image) {
            this.w=w; this.h=h; this.world=world; this.image=image;
        }
    }
    private final List<Sample> samples = new ArrayList<>();
    private static void load() { System.loadLibrary("opencv_java4"); }

    static Sample chess(Bitmap bitmap, Board board) {
        load();
        Mat rgba = new Mat(), gray = new Mat();
        try {
            Utils.bitmapToMat(bitmap, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            Sample sample = findChess(gray, board, 0);
            if (sample == null) throw new IllegalArgumentException("未找到完整棋盘格，请核对横竖格数");
            return sample;
        } finally {
            gray.release();
            rgba.release();
        }
    }

    static Sample chessPreview(byte[] nv21, int width, int height, Board board) {
        load();
        Mat gray = new Mat(height, width, CvType.CV_8UC1);
        try {
            gray.put(0, 0, nv21, 0, width * height);
            return findChess(gray, board, 960);
        } finally {
            gray.release();
        }
    }

    private static Sample findChess(Mat gray, Board board, int maxEdge) {
        Mat scaled = new Mat();
        MatOfPoint2f corners = new MatOfPoint2f();
        try {
            int width = gray.cols(), height = gray.rows();
            Mat input = gray;
            if (maxEdge > 0 && Math.max(width, height) > maxEdge) {
                double ratio = maxEdge / (double)Math.max(width, height);
                Imgproc.resize(gray, scaled, new Size(Math.round(width * ratio), Math.round(height * ratio)),
                    0, 0, Imgproc.INTER_AREA);
                input = scaled;
            }
            int cols = board.cols - 1, rows = board.rows - 1;
            if (!Calib3d.findChessboardCornersSB(input, new Size(cols, rows), corners,
                Calib3d.CALIB_CB_NORMALIZE_IMAGE)) return null;
            Point[] points = corners.toArray();
            if (points.length != cols * rows) return null;
            if (input != gray) {
                double sx = width / (double)input.cols(), sy = height / (double)input.rows();
                for (Point point : points) {
                    point.x = (point.x + .5) * sx - .5;
                    point.y = (point.y + .5) * sy - .5;
                }
                corners.fromArray(points);
                Imgproc.cornerSubPix(gray, corners, new Size(4,4), new Size(-1,-1),
                    new TermCriteria(TermCriteria.COUNT | TermCriteria.EPS, 20, .01));
                points = corners.toArray();
            }
            Point3[] world = new Point3[points.length];
            for (int i = 0; i < points.length; i++)
                world[i] = new Point3(i % cols * board.size, i / cols * board.size, 0);
            return new Sample(width, height, world, points);
        } finally {
            corners.release();
            scaled.release();
        }
    }

    static Sample orient(Sample raw, android.graphics.Matrix transform, int width, int height) {
        if (raw == null) return null;
        float[] coords = new float[raw.image.length * 2];
        for (int i = 0; i < raw.image.length; i++) {
            coords[2*i] = (float)raw.image[i].x + .5f;
            coords[2*i+1] = (float)raw.image[i].y + .5f;
        }
        transform.mapPoints(coords);
        Point[] points = new Point[raw.image.length];
        for (int i = 0; i < points.length; i++) points[i] = new Point(coords[2*i] - .5, coords[2*i+1] - .5);
        return new Sample(width, height, raw.world, points);
    }

    static final class BoardOriginException extends IllegalArgumentException {
        BoardOriginException(int first) {
            super("编号不匹配 · 点上方板型，将起始 ID 改为 " + first);
        }
    }

    static Sample april(ScanResult result, Board board) {
        if (result.tags.isEmpty()) throw new IllegalArgumentException("未读到标签 · 请对焦并稳住标定板");
        List<ScanResult.Tag> tags = new ArrayList<>();
        List<Point> cells = new ArrayList<>(), centers = new ArrayList<>();
        List<Double> widths = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        long end = (long) board.first + board.rows * board.cols;
        for (ScanResult.Tag tag : result.tags) {
            if (tag.id < board.first || tag.id >= end) continue;
            if (!seen.add(tag.id)) throw new IllegalArgumentException("标定板出现重复 ID，请核对板型");
            int index = tag.id - board.first;
            int row = index / board.cols;
            tags.add(tag);
            cells.add(new Point(index % board.cols, board.bottomFirst ? board.rows - 1 - row : row));
            centers.add(new Point(tag.x, tag.y));
            widths.add(Math.hypot(tag.corners[2] - tag.corners[0], tag.corners[3] - tag.corners[1]));
        }
        if (tags.size() < 6) {
            throw new IllegalArgumentException("读到 " + result.tags.size() + " 个标签，板内 "
                + tags.size() + " 个 · 至少需要 6 个");
        }

        load();
        Collections.sort(widths);
        double tolerance = Math.max(2, widths.get(widths.size() / 2) * .30);
        MatOfPoint2f grid = new MatOfPoint2f(cells.toArray(new Point[0]));
        MatOfPoint2f imageCenters = new MatOfPoint2f(centers.toArray(new Point[0]));
        Mat inliers = new Mat();
        Mat transform = null;
        MatOfPoint2f reference = new MatOfPoint2f(), projected = new MatOfPoint2f();
        try {
            transform = Calib3d.findHomography(grid, imageCenters, Calib3d.RANSAC, tolerance, inliers);
            int valid = inliers.empty() ? 0 : Core.countNonZero(inliers);
            if (result.tags.size() - valid >= 2) {
                int bestOrigin = board.first;
                int bestMatches = valid;
                for (long alternate : new long[]{(long)board.first - 1, (long)board.first + 1}) {
                    if (alternate < 0 || alternate > Integer.MAX_VALUE) continue;
                    int matches = countLayoutMatches(result.tags, board, (int)alternate, tolerance);
                    if (matches > bestMatches) {
                        bestMatches = matches;
                        bestOrigin = (int)alternate;
                    }
                }
                if (bestMatches >= valid + 2 && bestMatches >= result.tags.size() * .90) {
                    throw new BoardOriginException(bestOrigin);
                }
            }
            if (valid < 6 || valid < tags.size() * .85) {
                throw new IllegalArgumentException("标签位置与阵列不匹配 · 请核对行列和起始 ID");
            }

            // Use the grid only to match measured corners to physical board corners.
            // Some boards print each tag rotated relative to its cell.
            Point[] outline = new Point[tags.size() * 4];
            for (int i = 0; i < tags.size(); i++) {
                Point cell = cells.get(i);
                for (int c = 0; c < 4; c++) {
                    outline[4*i+c] = new Point(cell.x + (right(c) ? .35 : -.35),
                        cell.y + (c < 2 ? .35 : -.35));
                }
            }
            reference.fromArray(outline);
            Core.perspectiveTransform(reference, projected, transform);
            Point[] expected = projected.toArray();
            byte[] mask = new byte[tags.size()];
            inliers.get(0, 0, mask);
            List<Point3> world = new ArrayList<>();
            List<Point> pixels = new ArrayList<>();
            for (int i = 0; i < tags.size(); i++) {
                if (mask[i] == 0) continue;
                int offset = 4*i;
                Point a = expected[offset], b = expected[offset+1], c = expected[offset+2];
                if ((b.x-a.x)*(c.y-b.y) - (b.y-a.y)*(c.x-b.x) >= 0) {
                    throw new IllegalArgumentException("行方向不匹配 · 请检查「从底行开始编号」");
                }
                ScanResult.Tag tag = tags.get(i);
                int rotation = closestCornerRotation(tag, expected, offset);
                Point cell = cells.get(i);
                double left = cell.x * (board.size + board.gap), top = cell.y * (board.size + board.gap);
                for (int j = 0; j < 4; j++) {
                    int k = 2 * ((j + rotation) % 4);
                    world.add(new Point3(left + (right(j) ? board.size : 0),
                        top + (j < 2 ? board.size : 0), 0));
                    pixels.add(new Point(tag.corners[k], tag.corners[k+1]));
                }
            }
            return new Sample(result.width,result.height,world.toArray(new Point3[0]),pixels.toArray(new Point[0]));
        } finally {
            if (transform != null) transform.release();
            grid.release(); imageCenters.release(); inliers.release();
            reference.release(); projected.release();
        }
    }

    private static int countLayoutMatches(List<ScanResult.Tag> tags, Board board, int first, double tolerance) {
        List<Point> cells = new ArrayList<>(), centers = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (ScanResult.Tag tag : tags) {
            long index = (long)tag.id - first;
            if (index < 0 || index >= board.rows * board.cols) continue;
            if (!seen.add(tag.id)) return 0;
            int row = (int)index / board.cols;
            cells.add(new Point((int)index % board.cols, board.bottomFirst ? board.rows - 1 - row : row));
            centers.add(new Point(tag.x, tag.y));
        }
        if (cells.size() < 6) return 0;
        MatOfPoint2f grid = new MatOfPoint2f(cells.toArray(new Point[0]));
        MatOfPoint2f pixels = new MatOfPoint2f(centers.toArray(new Point[0]));
        Mat mask = new Mat(), fit = null;
        try {
            fit = Calib3d.findHomography(grid, pixels, Calib3d.RANSAC, tolerance, mask);
            return mask.empty() ? 0 : Core.countNonZero(mask);
        } finally {
            if (fit != null) fit.release();
            grid.release(); pixels.release(); mask.release();
        }
    }

    private static boolean right(int corner) { return corner == 1 || corner == 2; }

    private static int closestCornerRotation(ScanResult.Tag tag, Point[] expected, int offset) {
        int bestRotation = 0;
        double bestError = Double.POSITIVE_INFINITY;
        for (int rotation = 0; rotation < 4; rotation++) {
            double error = 0;
            for (int corner = 0; corner < 4; corner++) {
                int k = 2 * ((corner + rotation) % 4);
                Point target = expected[offset+corner];
                double dx = tag.corners[k] - target.x, dy = tag.corners[k+1] - target.y;
                error += dx*dx + dy*dy;
            }
            if (error < bestError) {
                bestError = error;
                bestRotation = rotation;
            }
        }
        return bestRotation;
    }

    synchronized int count() { return samples.size(); }
    synchronized void clear() { samples.clear(); }
    synchronized void add(Sample sample) {
        if (samples.size()>=40) throw new IllegalArgumentException("已有 40 张样本，请先计算标定");
        for (Sample earlier:samples) {
            if (sample.w!=earlier.w || sample.h!=earlier.h)
                throw new IllegalArgumentException("样本分辨率不同，请使用同一相机及分辨率");
            int matches=0; double distance=0;
            Map<String,Point> coords=new HashMap<>();
            for (int i=0;i<earlier.world.length;i++) coords.put(key(earlier.world[i]),earlier.image[i]);
            for (int i=0;i<sample.world.length;i++) {
                Point p=coords.get(key(sample.world[i]));
                if(p!=null){matches++;distance+=Math.hypot(p.x-sample.image[i].x,p.y-sample.image[i].y);}
            }
            if(matches>=sample.world.length*.6 && distance/matches<Math.max(sample.w,sample.h)*.018)
                throw new IllegalArgumentException("这个姿态和已有样本太接近，请移动或倾斜标定板");
        }
        samples.add(sample);
    }
    private static String key(Point3 p){return p.x+":"+p.y;}

    String solve(String camera, Board board, String family) throws JSONException {
        final List<Sample> views;
        synchronized(this) { views = new ArrayList<>(samples); }
        if(views.size()<8) throw new IllegalArgumentException("请先采集至少 8 张不同姿态的样本");
        load();
        List<Mat> world=new ArrayList<>(), pixels=new ArrayList<>(), r=new ArrayList<>(), t=new ArrayList<>();
        Mat k=Mat.eye(3,3,CvType.CV_64F), d=new Mat();
        Sample first=views.get(0);
        try{
            for(Sample sample:views){world.add(new MatOfPoint3f(sample.world));pixels.add(new MatOfPoint2f(sample.image));}
            double rms=Calib3d.calibrateCamera(world,pixels,new Size(first.w,first.h),k,d,r,t);
            double[] intrinsics=new double[9], distortion=new double[(int)d.total()];
            k.get(0,0,intrinsics);d.get(0,0,distortion);
            if(!Double.isFinite(rms)||intrinsics[0]<=0||intrinsics[4]<=0)
                throw new IllegalArgumentException("标定结果无效，请重新采集不同倾角的样本");
            JSONArray perView=new JSONArray();
            MatOfDouble coefficients=new MatOfDouble(distortion);
            try {
                for(int j=0;j<views.size();j++){
                    MatOfPoint2f projected=new MatOfPoint2f();
                    try {
                        Calib3d.projectPoints((MatOfPoint3f)world.get(j),r.get(j),t.get(j),k,coefficients,projected);
                        Point[] predicted=projected.toArray(), actual=views.get(j).image;
                        double sum=0;
                        for(int i=0;i<actual.length;i++) sum+=Math.pow(predicted[i].x-actual[i].x,2)+Math.pow(predicted[i].y-actual[i].y,2);
                        perView.put(Math.sqrt(sum/actual.length));
                    } finally {projected.release();}
                }
            } finally {coefficients.release();}
            return new JSONObject().put("camera",camera).put("mode",MODES[board.mode])
                .put("width",first.w).put("height",first.h).put("views",views.size())
                .put("board_type",board.mode==1?"checkerboard":board.hybrid?"charuco":"marker_grid")
                .put("marker_dictionary",board.mode==1?JSONObject.NULL:family)
                .put("marker_size_mm",board.hybrid?board.markerSize:board.mode==2?board.size:0)
                .put("legacy_charuco_pattern",board.hybrid&&board.legacy)
                .put("rows",board.rows).put("columns",board.cols).put("size_mm",board.size)
                .put("gap_mm",board.gap).put("first_id",board.first).put("bottom_first",board.bottomFirst)
                .put("distortion_model","plumb_bob").put("K",array(intrinsics)).put("D",array(distortion))
                .put("rms_px",rms).put("per_view_rms_px",perView).toString(2);
        } finally {
            for(List<Mat> group:Arrays.asList(world,pixels,r,t))for(Mat mat:group)mat.release();
            k.release();d.release();
        }
    }
    private static JSONArray array(double[] values) throws JSONException {
        JSONArray out=new JSONArray();for(double value:values)out.put(value);return out;
    }
}
