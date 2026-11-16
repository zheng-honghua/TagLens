package com.taglens.scanner;

import android.graphics.Bitmap;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.*;
import org.opencv.objdetect.Dictionary;

final class MarkerVision {
    static final int ARUCO_FIRST = 8;
    static final int ORIGINAL = ARUCO_FIRST + 16;
    static final int CHARUCO_DEFAULT = ARUCO_FIRST + 8;
    static final String[] FAMILIES = families();
    private static String[] families() {
        List<String> names = new ArrayList<>(Arrays.asList("tag36h11", "tag25h9", "tag16h5",
            "tagStandard41h12", "tagStandard52h13", "tagCircle21h7", "tagCircle49h12", "tagCustom48h12"));
        for (int side=4;side<=7;side++) for (int count:new int[]{50,100,250,1000})
            names.add("ArUco " + side + "X" + side + "_" + count);
        names.add("ArUco ORIGINAL");
        names.add("ArUco MIP 36h12");
        return names.toArray(new String[0]);
    }
    static boolean aruco(int family) { return family >= ARUCO_FIRST; }
    static boolean hybridSupported(int family) { return family < 3 || aruco(family); }
    private static int code(int family) {
        if (family == 0) return Objdetect.DICT_APRILTAG_36h11;
        if (family == 1) return Objdetect.DICT_APRILTAG_25h9;
        if (family == 2) return Objdetect.DICT_APRILTAG_16h5;
        if (family == ORIGINAL) return Objdetect.DICT_ARUCO_ORIGINAL;
        if (family == ORIGINAL+1) return Objdetect.DICT_ARUCO_MIP_36h12;
        if (aruco(family)) return family-ARUCO_FIRST;
        throw new IllegalArgumentException("此家族不支持混合棋盘，请选 ArUco 或 36h11 / 25h9 / 16h5");
    }

    private ArucoDetector arucoDetector;
    private ArucoDetector denseAprilDetector;
    private int arucoFamily = -1;
    private CharucoDetector hybridDetector;
    private CharucoBoard hybridPattern;
    private Calibration.Board hybridSettings;
    private int hybridFamily = -1;
    private Point3[] hybridWorld;

    float[] scanY(ByteBuffer bytes, int width, int height, int family) {
        System.loadLibrary("opencv_java4");
        ByteBuffer data = bytes.duplicate();
        data.clear();
        Mat gray = new Mat(height,width,CvType.CV_8UC1,data);
        try { return detect(gray,family,false); } finally {gray.release();}
    }

    float[] scanBitmap(Bitmap image, int family, boolean alternate) {
        System.loadLibrary("opencv_java4");
        Mat gray = grayBitmap(image);
        try { return detect(gray,family,alternate); } finally {gray.release();}
    }

    private static Mat grayBitmap(Bitmap image) {
        Mat rgba=new Mat(),gray=new Mat();
        try {
            Utils.bitmapToMat(image,rgba);
            Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY);
            return gray;
        } catch(RuntimeException error) {
            gray.release();
            throw error;
        } finally {rgba.release();}
    }

    private float[] detect(Mat gray, int family, boolean alternate) {
        if (arucoFamily != family) {
            Dictionary dictionary=Objdetect.getPredefinedDictionary(code(family));
            DetectorParameters params=new DetectorParameters();
            params.set_cornerRefinementMethod(Objdetect.CORNER_REFINE_SUBPIX);
            arucoDetector=new ArucoDetector(dictionary,params);
            arucoFamily=family;
        }
        Mat scaled=new Mat();
        try {
            if (alternate && Math.max(gray.cols(),gray.rows())>650) {
                Imgproc.resize(gray,scaled,new Size(),.75,.75,Imgproc.INTER_AREA);
                return readTags(gray, scaled, arucoDetector);
            }
            return readTags(gray, gray, arucoDetector);
        } finally { scaled.release(); }
    }

    float[] denseAprilY(ByteBuffer luma, int width, int height) {
        System.loadLibrary("opencv_java4");
        ByteBuffer data=luma.duplicate();
        data.clear();
        Mat gray=new Mat(height,width,CvType.CV_8UC1,data);
        try { return denseApril(gray); } finally { gray.release(); }
    }

    float[] denseAprilBitmap(Bitmap image) {
        System.loadLibrary("opencv_java4");
        Mat gray=grayBitmap(image);
        try { return denseApril(gray); } finally { gray.release(); }
    }

    private float[] denseApril(Mat gray) {
        if (denseAprilDetector==null) {
            Dictionary dictionary=Objdetect.getPredefinedDictionary(Objdetect.DICT_APRILTAG_36h11);
            dictionary.set_maxCorrectionBits(2);
            DetectorParameters parameters=new DetectorParameters();
            parameters.set_cornerRefinementMethod(Objdetect.CORNER_REFINE_SUBPIX);
            parameters.set_errorCorrectionRate(1);
            parameters.set_minMarkerDistanceRate(.025);
            parameters.set_adaptiveThreshWinSizeMax(75);
            parameters.set_perspectiveRemoveIgnoredMarginPerCell(.25);
            denseAprilDetector=new ArucoDetector(dictionary,parameters);
        }
        Mat scaled=new Mat(), blurred=new Mat();
        try {
            double scale=Math.min(3, 2500.0/Math.max(gray.cols(),gray.rows()));
            Imgproc.resize(gray,scaled,new Size(),scale,scale,Imgproc.INTER_CUBIC);
            Imgproc.GaussianBlur(scaled,blurred,new Size(0,0),1.4*scale);
            Core.addWeighted(scaled,2,blurred,-1,0,scaled);
            return readTags(gray,scaled,denseAprilDetector);
        } finally { scaled.release(); blurred.release(); }
    }

    private static float[] readTags(Mat gray, Mat input, ArucoDetector detector) {
        Mat ids=new Mat();
        List<Mat> corners=new ArrayList<>();
        try {
            detector.detectMarkers(input,corners,ids);
            float sx=gray.cols()/(float)input.cols(), sy=gray.rows()/(float)input.rows();
            float[] output=new float[corners.size()*13];
            int[] labels=new int[corners.size()];
            if(labels.length>0)ids.get(0,0,labels);
            for(int i=0;i<corners.size();i++){
                float[] xy=new float[8];
                corners.get(i).get(0,0,xy);
                int offset=i*13;
                output[offset]=labels[i];
                for(int j=0;j<4;j++){
                    int k=3-j; // OpenCV TL,TR,BR,BL → TagLens BL,BR,TR,TL.
                    float x=(xy[k*2]+.5f)*sx-.5f, y=(xy[k*2+1]+.5f)*sy-.5f;
                    output[offset+3+j*2]=x;
                    output[offset+4+j*2]=y;
                    output[offset+1]+=x*.25f;
                    output[offset+2]+=y*.25f;
                }
            }
            return output;
        } finally {
            for(Mat mat:corners)mat.release();
            ids.release();
        }
    }

    Calibration.Sample hybridY(byte[] nv21,int width,int height,int family,Calibration.Board board) {
        System.loadLibrary("opencv_java4");
        Mat gray=new Mat(height,width,CvType.CV_8UC1);
        try{
            gray.put(0,0,nv21,0,width*height);
            return hybrid(gray,family,board);
        } finally{gray.release();}
    }

    Calibration.Sample hybridBitmap(Bitmap image,int family,Calibration.Board board) {
        System.loadLibrary("opencv_java4");
        Mat gray=grayBitmap(image);
        try{return hybrid(gray,family,board);}finally{gray.release();}
    }

    private Calibration.Sample hybrid(Mat gray,int family,Calibration.Board board) {
        if(hybridSettings!=board || hybridFamily!=family){
            Dictionary dictionary=Objdetect.getPredefinedDictionary(code(family));
            int count=board.rows*board.cols/2;
            Mat bytes=dictionary.get_bytesList();
            try {
                if((long)board.first+count>bytes.rows())
                    throw new IllegalArgumentException("字典容量不足，请换更大的字典或核对起始 ID");
            } finally { bytes.release(); }
            int[] ids=new int[count];
            for(int i=0;i<count;i++) ids[i]=board.first+i;
            MatOfInt markerIds=new MatOfInt(ids);
            try {
                CharucoBoard pattern=new CharucoBoard(new Size(board.cols,board.rows),(float)board.size,
                    (float)board.markerSize,dictionary,markerIds);
                pattern.setLegacyPattern(board.legacy);
                hybridDetector=new CharucoDetector(pattern);
                hybridPattern=pattern;
                MatOfPoint3f world=pattern.getChessboardCorners();
                try{hybridWorld=world.toArray();}finally{world.release();}
                hybridSettings=board;
                hybridFamily=family;
            } finally{markerIds.release();}
        }
        MatOfPoint2f corners=new MatOfPoint2f();
        MatOfInt ids=new MatOfInt();
        try {
            hybridDetector.detectBoard(gray,corners,ids);
            if(ids.total()<8 || hybridPattern.checkCharucoCornersCollinear(ids))return null;
            int[] indices=ids.toArray();
            Point3[] world=new Point3[indices.length];
            for(int i=0;i<indices.length;i++)world[i]=hybridWorld[indices[i]];
            return new Calibration.Sample(gray.cols(),gray.rows(),world,corners.toArray());
        } finally{corners.release();ids.release();}
    }
}
