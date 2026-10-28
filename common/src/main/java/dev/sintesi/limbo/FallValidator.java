package dev.sintesi.limbo;

public final class FallValidator {

    public enum Result {
        PENDING,
        LANDED,
        FAILED
    }

    private static final int MAX_UNSYNCED = 200;
    private static final double HORIZONTAL_TOLERANCE = 0.05;
    private static final double MAX_AIR_STEP = 0.4;
    private static final double DISCARDED_MOVE_SQUARED = 1.0E-7;
    private static final double MAX_HORIZONTAL_OFFSET = LimboSession.PLATFORM_RADIUS + 0.5;

    private final double spawnX;
    private final double spawnZ;
    private final double tolerance;
    private final FallModel model;
    private final boolean tinyMovesDiscarded;
    private int unsynced;
    private int index = -1;
    private String failure;
    private double maxDeviation;
    private double lastX;
    private double lastZ;

    public FallValidator(double spawnX, double spawnY, double spawnZ, double groundY, double tolerance) {
        this(spawnX, spawnY, spawnZ, groundY, tolerance, true);
    }

    public FallValidator(double spawnX, double spawnY, double spawnZ, double groundY, double tolerance,
            boolean tinyMovesDiscarded) {
        this.tinyMovesDiscarded = tinyMovesDiscarded;
        this.spawnX = spawnX;
        this.spawnZ = spawnZ;
        this.tolerance = tolerance;
        this.model = new FallModel(spawnY, groundY);
    }

    public String failure() {
        return failure;
    }

    public double maxDeviation() {
        return maxDeviation;
    }

    public boolean synced() {
        return index >= 0;
    }

    public Result accept(double x, double y, double z, boolean onGround) {
        if (index < 0) {
            return acceptUnsynced(x, y, z);
        }
        double step = Math.hypot(x - lastX, z - lastZ);
        if (!(step <= MAX_AIR_STEP)
                || Math.abs(x - spawnX) > MAX_HORIZONTAL_OFFSET || Math.abs(z - spawnZ) > MAX_HORIZONTAL_OFFSET) {
            return fail("horizontal movement");
        }
        lastX = x;
        lastZ = z;
        double distanceToCurrent = Math.abs(y - model.heightAt(index));
        if (tinyMovesDiscarded && onGround && index == model.lastIndex() - 1 && distanceToCurrent <= tolerance) {
            double gap = model.heightAt(index) - model.heightAt(model.lastIndex());
            if (gap * gap <= DISCARDED_MOVE_SQUARED) {
                maxDeviation = Math.max(maxDeviation, distanceToCurrent);
                index = model.lastIndex();
                return Result.LANDED;
            }
        }
        double distanceToNext = index < model.lastIndex() ? Math.abs(y - model.heightAt(index + 1)) : Double.MAX_VALUE;
        if (distanceToNext <= tolerance && distanceToNext <= distanceToCurrent) {
            maxDeviation = Math.max(maxDeviation, distanceToNext);
            index++;
            if (index == model.lastIndex()) {
                return onGround ? Result.LANDED : fail("landed without ground flag");
            }
            return onGround ? fail("ground flag in mid air") : Result.PENDING;
        }
        if (distanceToCurrent <= tolerance) {
            return index == model.lastIndex() && onGround ? Result.LANDED : Result.PENDING;
        }
        return fail("height deviation");
    }

    private Result acceptUnsynced(double x, double y, double z) {
        boolean atSpawn = Math.abs(x - spawnX) <= HORIZONTAL_TOLERANCE
                && Math.abs(z - spawnZ) <= HORIZONTAL_TOLERANCE
                && Math.abs(y - model.heightAt(0)) <= tolerance;
        if (atSpawn) {
            index = 0;
            lastX = x;
            lastZ = z;
            return Result.PENDING;
        }
        if (++unsynced > MAX_UNSYNCED) {
            return fail("never reached spawn");
        }
        return Result.PENDING;
    }

    private Result fail(String reason) {
        failure = reason;
        return Result.FAILED;
    }
}
