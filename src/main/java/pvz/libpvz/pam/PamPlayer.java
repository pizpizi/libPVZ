package pvz.libpvz.pam;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.NumberUtils;

import pvz.libpvz.Matrix2D;
import pvz.libpvz.pam.BakedAnimation.Part;
import pvz.libpvz.textures.TextureBank;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/** A stateless renderer that decodes and plays PAM animations. It automatically fetches and loads textures from the TextureBank. */
public final class PamPlayer {
    private final TextureBank textures;
    private final FileHandle pamFolder;

    private final Map<String, PamData> animationByPath = new HashMap<>();
    private final Map<String, BakedAnimation> bakedByPath = new HashMap<>();
    private final Map<String, List<Runnable>> loadingCallbacks = new HashMap<>();
    private final LinkedBlockingQueue<String> loadQueue = new LinkedBlockingQueue<>();

    // temporary rendering variables
    private final float[] vertices = new float[20];
    private final Matrix2D root = new Matrix2D();
    private final Color tint = new Color();
    private final int[] tmpPartQueue = new int[1000];
    private final boolean[] tmpPartVisible = new boolean[1000];
    private final boolean[] tmpPartWhitelisted = new boolean[1000];

    /**
     * Initializes the PamPlayer.
     * @param textures The TextureBank used to resolve images.
     * @param assetsFolder The root directory containing the 'pam' folder.
     */
    public PamPlayer(TextureBank textures, FileHandle assetsFolder) {
        this.textures = textures;
        FileHandle pFolder = assetsFolder.child("pam");
        if (!pFolder.exists()) {
            pFolder = assetsFolder.child("IMAGES");
        }
        this.pamFolder = pFolder;
        Thread worker = new Thread(() -> {
            while (true) {
                try {
                    String pam = loadQueue.take();
                    PamData anim = readAnimationFile(pam);
                    List<String> requiredAtlases = new ArrayList<>();
                    for (PamData.Image img : anim.images) {
                        pvz.libpvz.textures.ResourceIndex.ImageEntry entry = textures.getResourceIndex()
                                .image(img.resourceId);
                        if (entry != null && entry.atlasId != null && !requiredAtlases.contains(entry.atlasId)) {
                            requiredAtlases.add(entry.atlasId);
                        }
                    }
                    BakedAnimation ba = new BakedAnimation(anim, textures.getResourceIndex());
                    Runnable finalizeTask = () -> {
                        if (!bakedByPath.containsKey(pam)) {
                            ba.initializeTextures(textures);
                            bakedByPath.put(pam, ba);
                        }
                        List<Runnable> callbacks = loadingCallbacks.remove(pam);
                        if (callbacks != null) {
                            for (Runnable cb : callbacks) {
                                if (cb != null) cb.run();
                            }
                        }
                    };
                    if (requiredAtlases.isEmpty()) {
                        Gdx.app.postRunnable(finalizeTask);
                    } else {
                        Gdx.app.postRunnable(() -> {
                            java.util.concurrent.atomic.AtomicInteger loadedCount = new java.util.concurrent.atomic.AtomicInteger(0);
                            for (String atlas : requiredAtlases) {
                                textures.loadAsync(atlas, () -> {
                                    if (loadedCount.incrementAndGet() == requiredAtlases.size()) {
                                        finalizeTask.run();
                                    }
                                });
                            }
                        });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        worker.setDaemon(true);
        worker.setName("PamPlayerWorker");
        worker.start();
    }
    private PamData readAnimationFile(String animationPath) {
        return readAnimationFile(new FileHandle(pamFolder.path() + "/" + animationPath));
    }
    
    private PamData readAnimationFile(FileHandle pamFile) {
        String key = pamFile.path();
        PamData cached = animationByPath.get(key);
        if (cached != null) {
            return cached;
        }
        PamData parsed = PamBinaryReader.parse(readAllBytes(pamFile));
        animationByPath.put(key, parsed);
        return parsed;
    }
    

    // draw methods
    /**
     * Renders a PAM animation clip at its native resolution.
     * 
     * <p><b>Note:</b> By default, this method hides some
     * parts such as "armor", "custom", "ground_swatch", "ink", and "butter". these should be
     * manually made visible via the partsVisibility parameter. 
     * 
     * @param pam The relative path to the PAM file.
     * @param clip The specific animation clip name (e.g. "idle").
     * @param time The current playback time in seconds.
     */
    public void draw(Batch batch, String pam, String clip, float time, float x, float y, boolean loop) {
        BakedAnimation ba = bakedAsync(pam);
        if (ba != null) drawInternal(batch, ba, ba.range(clip), time, loop, x, y, 1f, 1f, null,
            null);
    }
    public void draw(Batch batch, String pam, String clip, float time, float x, float y, boolean loop,
            Map<String, Boolean> partsVisibility) {
        BakedAnimation ba = bakedAsync(pam);
        if (ba != null) drawInternal(batch, ba, ba.range(clip), time, loop, x, y,
                1f, 1f, partsVisibility, null);
    }
    public void draw(Batch batch, String pam, String clip, float time, float x, float y,
                     float scaleX, float scaleY,
                     boolean loop,
                     Map<String, Boolean> partsVisibility) {
        BakedAnimation ba = bakedAsync(pam);
        if (ba != null) drawInternal(batch, ba, ba.range(clip), time, loop, x, y,
                scaleX, scaleY, partsVisibility, null);
    }
    public void drawPart(Batch batch, String pam, String clip, float time, float x, float y, String part) {
        BakedAnimation ba = bakedAsync(pam);
        if (ba != null) drawInternal(batch, ba, ba.range(clip), time, true, x, y, 1f, 1f, null,
            part);
    }


    /**
     * Retrieves an optimized clip handle to eliminate String lookups during rendering.
     * @return The cached clip, or null if the animation is not yet loaded.
     */
    public ClipRef getClip(String pam, String clip) {
        BakedAnimation ba = bakedAsync(pam);
        if (ba == null) return null;
        int[] range = ba.range(clip);
        float duration = (range[1] - range[0] + 1) / ba.frameRate;
        return new ClipRef(ba, range, duration);
    }

    /**
     * Renders a PAM animation clip using an optimized, cached handle.
     * This avoids expensive String lookups in the hot render loop.
     */
    public void draw(Batch batch, ClipRef clip, float time, float x, float y, boolean loop) {
        if (clip != null) {
            drawInternal(batch, clip.ba, clip.range, time, loop, x, y, 1f, 1f, null, null);
        }
    }

    /**
     * Renders a PAM animation with scale fields.
     */
    public void draw(Batch batch, String pam, String clip, float time, float x, float y, float scaleX, float scaleY, boolean loop) {
        BakedAnimation ba = bakedAsync(pam);
        if (ba != null) drawInternal(batch, ba, ba.range(clip), time, loop, x, y, scaleX, scaleY, null, null);
    }

    /**
     * Renders a PAM animation using ClipRef with scale fields.
     */
    public void draw(Batch batch, ClipRef clip, float time, float x, float y, float scaleX,
                     float scaleY,
                     boolean loop) {
        if (clip != null) {
            drawInternal(batch, clip.ba, clip.range, time, loop, x, y, scaleX, scaleY, null, null);
        }
    }

    /**
     * Renders a PAM animation clip using an optimized, cached handle, with custom visibility.
     */
    public void draw(Batch batch, ClipRef clip, float time, float x, float y, boolean loop, Map<String, Boolean> partsVisibility) {
        if (clip != null) {
            drawInternal(batch, clip.ba, clip.range, time, loop, x, y, 1f, 1f, partsVisibility,
                    null);
        }
    }

    public void draw(Batch batch, ClipRef clip, float time, float x, float y,
                     float scaleX, float scaleY, boolean loop,
                     Map<String, Boolean> partsVisibility) {
        if (clip != null) {
            drawInternal(batch, clip.ba, clip.range, time, loop, x, y, scaleX, scaleY, partsVisibility,
                    null);
        }
    }

    // clip info

    /** Retrieves the physical bounds (width and height) of a specific animation clip. as these
     * methods need the parsed pam data, they will load it synced.
    */
    public Rectangle bounds(String pam, String clip) {
        BakedAnimation ba = bakedSync(pam);
        return ba != null ? ba.bounds(clip) : null;
    }
    public Rectangle bounds(String pam) {
        BakedAnimation ba = bakedSync(pam);
        return ba != null ? ba.bounds(null) : null;
    }
    public List<String> clips(String pam) {
        BakedAnimation ba = bakedSync(pam);
        return ba != null ? ba.clips() : null;
    }
    
    /** Returns the total duration of the clip in seconds. */
    public float clipDurationSeconds(String pam, String clip) {
        BakedAnimation ba = bakedSync(pam);
        if (ba == null)
            return 1f;
        int[] r = ba.range(clip);
        return (r[1] - r[0] + 1) / ba.frameRate;
    }

    public static class AnimationPart {
        public final int id;
        public final String name;
        public final boolean resource;
        public final List<AnimationPart> children;
        public AnimationPart(int id, String name, boolean resource) {
            this.id = id;
            this.name = name;
            this.resource = resource;
            this.children = new ArrayList<>();
        }
    }
    private AnimationPart recursivePartBuild(Part part) {
        AnimationPart animPart = new AnimationPart(part.id, part.name, part.texture != null);
        for (Part child : part.children) {
            animPart.children.add(recursivePartBuild(child));
        }
        return animPart;
    }

    // not really usefull. only for the browser.
    public AnimationPart getParts(String pam) {
        BakedAnimation ba = bakedSync(pam);
        return ba != null ? recursivePartBuild(ba.rootPart) : null;
    }

    
    /** Preloads a PAM animation and all its associated textures synchronously on the current thread. */
    public void loadSync(String pam) {
        if (bakedByPath.containsKey(pam)) {
            return;
        }
        PamData anim = readAnimationFile(pam);
        List<String> requiredAtlases = new ArrayList<>();
        for (PamData.Image img : anim.images) {
            pvz.libpvz.textures.ResourceIndex.ImageEntry entry = textures.getResourceIndex()
                    .image(img.resourceId);
            if (entry != null && entry.atlasId != null && !requiredAtlases.contains(entry.atlasId)) {
                requiredAtlases.add(entry.atlasId);
            }
        }
        for (String atlas : requiredAtlases) {
            textures.loadSync(atlas);
        }
        BakedAnimation ba = new BakedAnimation(anim, textures.getResourceIndex());
        ba.initializeTextures(textures);
        bakedByPath.put(pam, ba);
    }

    /** Preloads a PAM animation and all its associated textures asynchronously. */
    public void loadAsync(String pam, Runnable onLoaded) {
        if (bakedByPath.containsKey(pam)) {
            if (onLoaded != null)
                onLoaded.run();
            return;
        }
        List<Runnable> callbacks = loadingCallbacks.get(pam);
        if (callbacks != null) {
            if (onLoaded != null)
                callbacks.add(onLoaded);
        } else {
            callbacks = new ArrayList<>();
            if (onLoaded != null)
                callbacks.add(onLoaded);
            loadingCallbacks.put(pam, callbacks);
            loadQueue.add(pam);
        }
    }

    // internal methods

    private BakedAnimation bakedAsync(String pam) {
        BakedAnimation ba = bakedByPath.get(pam);
        if (ba == null) {
            loadAsync(pam, null);
        }
        return ba;
    }

    private BakedAnimation bakedSync(String pam) {
        BakedAnimation ba = bakedByPath.get(pam);
        if (ba == null) {
            loadSync(pam);
            ba = bakedByPath.get(pam);
        }
        return ba;
    }

    /**
     * The core rendering engine for all PAM animations. This method translates high-level playback state 
     * (time, clip, position) into raw vertex geometry submitted to the LibGDX Batch.
     * 
     * <p><b>How it works:</b>
     * <ol>
     * <li><b>Coordinate Transformation:</b> Sets up the root transformation matrix. Because PAM files 
     * inherently use a Y-down coordinate system (0,0 at top-left) and LibGDX uses Y-up (0,0 at bottom-left), 
     * this matrix flips the Y-axis (-1f scale) and centers the animation based on its native canvas size.</li>
     * <li><b>Frame Resolution:</b> Multiplies the elapsed time by the animation's framerate to determine 
     * the discrete frame index. It clamps or modulo-wraps this index based on the clip's specific frame 
     * range and whether looping is enabled.</li>
     * <li><b>Visibility Traversal (BFS):</b> Uses a highly optimized, allocation-free Breadth-First-Search 
     * queue (`tmpPartQueue`) to traverse the animation's part hierarchy. It evaluates parent-child visibility 
     * rules, checking custom visibility maps or explicit whitelists.</li>
     * <li><b>Vertex Construction:</b> Iterates linearly over the pre-baked quads of the active frame. 
     * For every visible part, it applies the root transform to the raw corners, computes the final tinted color, 
     * maps the UV texture coordinates, and submits the 20-float vertex array to the Batch.</li>
     * </ol>
     */
    private void drawInternal(Batch batch, BakedAnimation ba, int[] range, float time, boolean loop,
            float x, float y, float scaleX, float scaleY, Map<String, Boolean> partVisibility,
                              String whiteListedPart) {
        if (ba == null || ba.frames.length == 0)
            return;

        float cw = ba.canvasWidth > 0f ? ba.canvasWidth : 1f;
        float ch = ba.canvasHeight > 0f ? ba.canvasHeight : 1f;
        root.set(scaleX, 0f, 0f, -scaleY, x - (cw*scaleX) / 2f, y + (ch*scaleY) / 2f);

        int span = Math.max(1, range[1] - range[0] + 1);
        int fi = (int) Math.floor(time * ba.frameRate);
        if (fi < 0) {
            fi = 0;
        }
        int local = loop ? ((fi % span) + span) % span : Math.min(fi, span - 1);
        BakedAnimation.BakedFrame frame = ba.frames[range[0] + local];

        Color batchColor = batch.getColor();
        boolean white = batchColor.r == 1f && batchColor.g == 1f && batchColor.b == 1f && batchColor.a == 1f;
        float[] v = vertices;
        Part[] parts = ba.parts;
        for (int i = 0; i < parts.length; i++) {
            tmpPartVisible[i] = false;
        }
        int partQueueIdx = 0;
        int partQueueHead = 1;
        tmpPartQueue[0] = ba.rootPart.id;
        while (partQueueIdx < partQueueHead) {
            int currentPartId = tmpPartQueue[partQueueIdx];
            Part part = parts[currentPartId];
            String name = part.name;
            partQueueIdx++;
            boolean mapVisible = false;
            if (partVisibility != null && name != null) {
                Boolean visible = partVisibility.get(name);
                if (visible != null) {
                    if (!visible) continue;
                    mapVisible = true;
                }
            }

            if (whiteListedPart == null && !mapVisible && part.flags != 0)
                continue;
            boolean inWhitelist = false;
            if (whiteListedPart != null) {
                if (whiteListedPart.equals(name)) {
                    inWhitelist = true;
                } else if (part.parent != null && tmpPartWhitelisted[part.parent.id]) {
                    inWhitelist = true;
                }
            }
            tmpPartWhitelisted[currentPartId] = inWhitelist;
            for (int j = 0; j < part.children.size(); j++) {
                int child = part.children.get(j).id;
                tmpPartQueue[partQueueHead] = child;
                partQueueHead++;
            }
            tmpPartVisible[currentPartId] = true;
        }
        for (int i = 0; i < frame.count; i++) {
            int partId = frame.partIds[i];
            if (!tmpPartVisible[partId])
                continue;

            if (whiteListedPart != null && !tmpPartWhitelisted[partId])
                continue;
            Part part = parts[partId];
            if (part.texture == null || part.uv == null)
                continue;
            int c8 = i * 8;
            float packed = white ? frame.colors[i] : multiply(frame.colors[i], batchColor);
            float u = part.uv[0], vv = part.uv[1], u2 = part.uv[2], v2 = part.uv[3];
            v[Batch.X1] = root.worldX(frame.corners[c8], frame.corners[c8 + 1]);
            v[Batch.Y1] = root.worldY(frame.corners[c8], frame.corners[c8 + 1]);
            v[Batch.C1] = packed;
            v[Batch.U1] = u;
            v[Batch.V1] = vv;
            v[Batch.X2] = root.worldX(frame.corners[c8 + 2], frame.corners[c8 + 3]);
            v[Batch.Y2] = root.worldY(frame.corners[c8 + 2], frame.corners[c8 + 3]);
            v[Batch.C2] = packed;
            v[Batch.U2] = u;
            v[Batch.V2] = v2;
            v[Batch.X3] = root.worldX(frame.corners[c8 + 4], frame.corners[c8 + 5]);
            v[Batch.Y3] = root.worldY(frame.corners[c8 + 4], frame.corners[c8 + 5]);
            v[Batch.C3] = packed;
            v[Batch.U3] = u2;
            v[Batch.V3] = v2;
            v[Batch.X4] = root.worldX(frame.corners[c8 + 6], frame.corners[c8 + 7]);
            v[Batch.Y4] = root.worldY(frame.corners[c8 + 6], frame.corners[c8 + 7]);
            v[Batch.C4] = packed;
            v[Batch.U4] = u2;
            v[Batch.V4] = vv;
            batch.draw(part.texture, v, 0, 20);
        }
    }
    private float multiply(float packed, Color batchColor) {
        int bits = NumberUtils.floatToIntColor(packed);
        float a = ((bits >>> 24) & 0xff) / 255f;
        float b = ((bits >>> 16) & 0xff) / 255f;
        float g = ((bits >>> 8) & 0xff) / 255f;
        float r = (bits & 0xff) / 255f;
        tint.set(r * batchColor.r, g * batchColor.g, b * batchColor.b, a * batchColor.a);
        return tint.toFloatBits();
    }
    private static byte[] readAllBytes(FileHandle file) {
        try {
            return Files.readAllBytes(file.file().toPath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read PAM: " + file, e);
        }
    }
}