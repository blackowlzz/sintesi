package dev.sintesi.limbo;

import java.util.SplittableRandom;

public record LimboWorld(int chunkX, int chunkZ, int platformX, int platformY, int platformZ,
        double spawnX, double spawnY, double spawnZ, PlatformBlock block) {

    private static final int CHUNK_RANGE = 50_000;

    public static LimboWorld random(SplittableRandom random) {
        return random(random, true);
    }

    public static LimboWorld random(SplittableRandom random, boolean visiblePlatform) {
        int chunkX = random.nextInt(-CHUNK_RANGE, CHUNK_RANGE);
        int chunkZ = random.nextInt(-CHUNK_RANGE, CHUNK_RANGE);
        int margin = LimboSession.PLATFORM_RADIUS;
        int platformX = chunkX * 16 + margin + random.nextInt(16 - 2 * margin);
        int platformZ = chunkZ * 16 + margin + random.nextInt(16 - 2 * margin);
        int platformY = 70 + random.nextInt(31);
        double fall = 4 + random.nextInt(4) + random.nextDouble() * 0.9;
        double spawnX = platformX + 0.2 + random.nextDouble() * 0.6;
        double spawnZ = platformZ + 0.2 + random.nextDouble() * 0.6;
        PlatformBlock[] visible = PlatformBlock.visible();
        PlatformBlock block = visiblePlatform ? visible[random.nextInt(visible.length)] : PlatformBlock.BARRIER;
        return new LimboWorld(chunkX, chunkZ, platformX, platformY, platformZ,
                spawnX, platformY + block.height() + fall, spawnZ, block);
    }

    public double groundY() {
        return platformY + block.height();
    }
}
