package dev.sintesi.limbo;

import java.util.ArrayList;
import java.util.List;

public final class FallModel {

    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.9800000190734863;
    private static final int MAX_STEPS = 4000;

    private final double[] heights;

    public FallModel(double startY, double groundY) {
        List<Double> sequence = new ArrayList<>();
        double y = startY;
        double velocity = 0;
        sequence.add(y);
        for (int i = 0; i < MAX_STEPS; i++) {
            velocity = (velocity - GRAVITY) * DRAG;
            double next = y + velocity;
            if (next <= groundY) {
                sequence.add(groundY);
                break;
            }
            y = next;
            sequence.add(y);
        }
        heights = new double[sequence.size()];
        for (int i = 0; i < heights.length; i++) {
            heights[i] = sequence.get(i);
        }
    }

    public int steps() {
        return heights.length;
    }

    public double heightAt(int index) {
        return heights[index];
    }

    public int lastIndex() {
        return heights.length - 1;
    }
}
