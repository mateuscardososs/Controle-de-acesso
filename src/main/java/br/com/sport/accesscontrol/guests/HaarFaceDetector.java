package br.com.sport.accesscontrol.guests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure-Java Viola–Jones frontal face detector. It parses an OpenCV Haar cascade
 * ({@code haarcascade_frontalface_alt.xml}) once at start-up and evaluates it with integral images.
 *
 * <p>No native libraries are used, so the detector works unchanged on the Alpine/musl runtime image.
 * Accuracy is sufficient for enrolment photos (single, roughly frontal face on a clean background).
 */
@Component
public class HaarFaceDetector implements FaceDetector {

    private static final Logger log = LoggerFactory.getLogger(HaarFaceDetector.class);
    private static final String CASCADE_RESOURCE = "face/haarcascade_frontalface_alt.xml";

    /** Down-scale the analysed image so detection cost is bounded regardless of upload resolution. */
    private static final int MAX_DETECTION_DIMENSION = 640;
    /** Pyramid step between successive window sizes. */
    private static final double SCALE_FACTOR = 1.08;
    /** Sliding-window shift, in base-window pixels, scaled with the window. */
    private static final int SHIFT = 2;
    /**
     * Minimum overlapping detections required to keep a face. A lower threshold is intentional here:
     * enrolment validation must not discard a smaller/background face and then approve a group photo.
     * The processor decides whether secondary detections are relevant enough to reject.
     */
    private static final int MIN_NEIGHBORS = 2;

    private final Cascade cascade;

    public HaarFaceDetector() {
        this.cascade = loadCascade();
        log.info("HAAR_CASCADE_LOADED window={}x{} stages={} features={}",
                cascade.width, cascade.height, cascade.stages.length, cascade.features.length);
    }

    @Override
    public List<FaceBox> detect(BufferedImage image) {
        if (image == null) {
            return List.of();
        }
        // Work on a bounded-size grayscale copy and remember the scale back to the original.
        double downscale = Math.min(1.0,
                (double) MAX_DETECTION_DIMENSION / Math.max(image.getWidth(), image.getHeight()));
        int workWidth = Math.max(1, (int) Math.round(image.getWidth() * downscale));
        int workHeight = Math.max(1, (int) Math.round(image.getHeight() * downscale));
        int[][] gray = grayscale(image, workWidth, workHeight);

        long[][] sum = new long[workHeight + 1][workWidth + 1];
        long[][] sqSum = new long[workHeight + 1][workWidth + 1];
        integralImages(gray, sum, sqSum);

        List<FaceBox> candidates = new ArrayList<>();
        double scale = 1.0;
        while (cascade.width * scale <= workWidth && cascade.height * scale <= workHeight) {
            int winW = (int) (cascade.width * scale);
            int winH = (int) (cascade.height * scale);
            int step = Math.max(1, (int) (scale * SHIFT));
            double invArea = 1.0 / ((double) winW * winH);
            for (int y = 0; y + winH < workHeight; y += step) {
                for (int x = 0; x + winW < workWidth; x += step) {
                    if (passesCascade(sum, sqSum, x, y, winW, winH, scale, invArea)) {
                        candidates.add(new FaceBox(x, y, winW, winH));
                    }
                }
            }
            scale *= SCALE_FACTOR;
        }

        List<FaceBox> grouped = groupRectangles(candidates, MIN_NEIGHBORS).stream()
                .sorted(Comparator.comparingInt(FaceBox::area).reversed())
                .toList();
        if (downscale >= 1.0) {
            return grouped;
        }
        // Map grouped boxes back to original image coordinates.
        double inv = 1.0 / downscale;
        List<FaceBox> result = new ArrayList<>(grouped.size());
        for (FaceBox box : grouped) {
            result.add(new FaceBox(
                    (int) Math.round(box.x() * inv),
                    (int) Math.round(box.y() * inv),
                    (int) Math.round(box.width() * inv),
                    (int) Math.round(box.height() * inv)));
        }
        return result.stream()
                .sorted(Comparator.comparingInt(FaceBox::area).reversed())
                .toList();
    }

    private boolean passesCascade(long[][] sum, long[][] sqSum, int x, int y, int winW, int winH,
                                  double scale, double invArea) {
        double windowSum = rectSum(sum, x, y, winW, winH);
        double windowSqSum = rectSum(sqSum, x, y, winW, winH);
        double mean = windowSum * invArea;
        double variance = windowSqSum * invArea - mean * mean;
        double std = variance > 1.0 ? Math.sqrt(variance) : 1.0;

        for (Stage stage : cascade.stages) {
            double stageSum = 0.0;
            for (Stump stump : stage.stumps) {
                Feature feature = cascade.features[stump.featureIndex];
                double featureSum = 0.0;
                for (Rect rect : feature.rects) {
                    // Scale the rect corners (matching OpenCV) rather than the width/height, so
                    // rounding stays consistent with how the cascade was trained.
                    int rx1 = x + (int) (rect.x * scale);
                    int ry1 = y + (int) (rect.y * scale);
                    int rx2 = x + (int) ((rect.x + rect.w) * scale);
                    int ry2 = y + (int) ((rect.y + rect.h) * scale);
                    double rs = sum[ry2][rx2] - sum[ry1][rx2] - sum[ry2][rx1] + sum[ry1][rx1];
                    featureSum += rect.weight * rs;
                }
                double normalized = featureSum * invArea;
                stageSum += normalized < stump.threshold * std ? stump.leftValue : stump.rightValue;
            }
            if (stageSum < stage.threshold) {
                return false;
            }
        }
        return true;
    }

    private static double rectSum(long[][] integral, int x, int y, int w, int h) {
        return integral[y + h][x + w] - integral[y][x + w] - integral[y + h][x] + integral[y][x];
    }

    private static void integralImages(int[][] gray, long[][] sum, long[][] sqSum) {
        int height = gray.length;
        int width = gray[0].length;
        for (int y = 1; y <= height; y++) {
            long rowSum = 0;
            long rowSqSum = 0;
            for (int x = 1; x <= width; x++) {
                int value = gray[y - 1][x - 1];
                rowSum += value;
                rowSqSum += (long) value * value;
                sum[y][x] = sum[y - 1][x] + rowSum;
                sqSum[y][x] = sqSum[y - 1][x] + rowSqSum;
            }
        }
    }

    private static int[][] grayscale(BufferedImage image, int width, int height) {
        int[][] gray = new int[height][width];
        double sx = (double) image.getWidth() / width;
        double sy = (double) image.getHeight() / height;
        for (int y = 0; y < height; y++) {
            int srcY = Math.min(image.getHeight() - 1, (int) (y * sy));
            for (int x = 0; x < width; x++) {
                int srcX = Math.min(image.getWidth() - 1, (int) (x * sx));
                int rgb = image.getRGB(srcX, srcY);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                // ITU-R BT.601 luma.
                gray[y][x] = (int) (0.299 * r + 0.587 * g + 0.114 * b);
            }
        }
        return gray;
    }

    /**
     * OpenCV-style rectangle grouping: clusters overlapping detections and keeps only clusters with
     * at least {@code minNeighbors} members, returning the averaged rectangle of each surviving cluster.
     */
    private static List<FaceBox> groupRectangles(List<FaceBox> rects, int minNeighbors) {
        int n = rects.size();
        int[] labels = new int[n];
        for (int i = 0; i < n; i++) {
            labels[i] = -1;
        }
        int classes = 0;
        for (int i = 0; i < n; i++) {
            if (labels[i] != -1) {
                continue;
            }
            labels[i] = classes;
            assignCluster(rects, labels, i, classes);
            classes++;
        }

        long[] sx = new long[classes];
        long[] sy = new long[classes];
        long[] sw = new long[classes];
        long[] sh = new long[classes];
        int[] counts = new int[classes];
        for (int i = 0; i < n; i++) {
            int c = labels[i];
            FaceBox r = rects.get(i);
            sx[c] += r.x();
            sy[c] += r.y();
            sw[c] += r.width();
            sh[c] += r.height();
            counts[c]++;
        }

        List<FaceBox> result = new ArrayList<>();
        for (int c = 0; c < classes; c++) {
            if (counts[c] < minNeighbors) {
                continue;
            }
            int k = counts[c];
            result.add(new FaceBox(
                    Math.round((float) sx[c] / k),
                    Math.round((float) sy[c] / k),
                    Math.round((float) sw[c] / k),
                    Math.round((float) sh[c] / k)));
        }
        return result;
    }

    private static void assignCluster(List<FaceBox> rects, int[] labels, int seed, int cluster) {
        for (int j = 0; j < rects.size(); j++) {
            if (labels[j] == -1 && similar(rects.get(seed), rects.get(j))) {
                labels[j] = cluster;
                assignCluster(rects, labels, j, cluster);
            }
        }
    }

    private static boolean similar(FaceBox a, FaceBox b) {
        double delta = 0.2 * (Math.min(a.width(), b.width()) + Math.min(a.height(), b.height())) * 0.5;
        return Math.abs(a.x() - b.x()) <= delta
                && Math.abs(a.y() - b.y()) <= delta
                && Math.abs(a.x() + a.width() - b.x() - b.width()) <= delta
                && Math.abs(a.y() + a.height() - b.y() - b.height()) <= delta;
    }

    private static Cascade loadCascade() {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            try (InputStream input = new ClassPathResource(CASCADE_RESOURCE).getInputStream()) {
                var document = factory.newDocumentBuilder().parse(input);
                Element cascade = (Element) document.getElementsByTagName("cascade").item(0);
                int width = intText(cascade, "width");
                int height = intText(cascade, "height");

                List<Feature> features = new ArrayList<>();
                Element featuresEl = firstChildElement(cascade, "features");
                for (Element featureEl : childElements(featuresEl, "_")) {
                    Element rectsEl = firstChildElement(featureEl, "rects");
                    List<Rect> rects = new ArrayList<>();
                    for (Element rectEl : childElements(rectsEl, "_")) {
                        String[] parts = rectEl.getTextContent().trim().split("\\s+");
                        rects.add(new Rect(
                                Integer.parseInt(parts[0]),
                                Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[2]),
                                Integer.parseInt(parts[3]),
                                Double.parseDouble(parts[4])));
                    }
                    features.add(new Feature(rects.toArray(new Rect[0])));
                }

                List<Stage> stages = new ArrayList<>();
                Element stagesEl = firstChildElement(cascade, "stages");
                for (Element stageEl : childElements(stagesEl, "_")) {
                    double stageThreshold = Double.parseDouble(text(stageEl, "stageThreshold"));
                    Element weakEl = firstChildElement(stageEl, "weakClassifiers");
                    List<Stump> stumps = new ArrayList<>();
                    for (Element classifierEl : childElements(weakEl, "_")) {
                        String[] node = text(classifierEl, "internalNodes").trim().split("\\s+");
                        String[] leaf = text(classifierEl, "leafValues").trim().split("\\s+");
                        int featureIndex = Integer.parseInt(node[2]);
                        double threshold = Double.parseDouble(node[3]);
                        stumps.add(new Stump(featureIndex, threshold,
                                Double.parseDouble(leaf[0]), Double.parseDouble(leaf[1])));
                    }
                    stages.add(new Stage(stageThreshold, stumps.toArray(new Stump[0])));
                }

                return new Cascade(width, height,
                        stages.toArray(new Stage[0]), features.toArray(new Feature[0]));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not load Haar cascade resource " + CASCADE_RESOURCE, exception);
        }
    }

    private static int intText(Element parent, String tag) {
        return Integer.parseInt(text(parent, tag));
    }

    private static String text(Element parent, String tag) {
        return firstChildElement(parent, tag).getTextContent();
    }

    private static Element firstChildElement(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tag.equals(child.getNodeName())) {
                return (Element) child;
            }
        }
        throw new IllegalStateException("Missing <" + tag + "> in cascade definition.");
    }

    private static List<Element> childElements(Element parent, String tag) {
        List<Element> elements = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tag.equals(child.getNodeName())) {
                elements.add((Element) child);
            }
        }
        return elements;
    }

    private record Cascade(int width, int height, Stage[] stages, Feature[] features) {
    }

    private record Stage(double threshold, Stump[] stumps) {
    }

    private record Stump(int featureIndex, double threshold, double leftValue, double rightValue) {
    }

    private record Feature(Rect[] rects) {
    }

    private record Rect(int x, int y, int w, int h, double weight) {
    }
}
