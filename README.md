# libPVZ
A lightweight LibGDX library for parsing and rendering PopCap/EA Plants vs. Zombies 2 PAM animation files.

## Features
- **Pre-Baked Renderer:** Bakes PAM hierarchy trees into static frame quad arrays.
- **Asynchronous Loading:** Non-blocking background PAM binary parsing and texture atlas loading.
- **Stateless Architecture:** A single `PamPlayer` instance can draw unique entities concurrently without allocating per-instance state.
- **Visibility Maps:** Toggle individual character parts, armor states (buckets, helmets), and status effects (butter, frozen).
- **Part Queries:** Locate any named part on any frame, to drive game logic from the animation itself.

## Requirements
- **Java:** 8+
- **LibGDX:** 1.12.1+
- **Gradle:** 7.0+

## Installation
Add the JitPack repository and dependency to your `build.gradle`:
```groovy
repositories {
    maven { url 'https://jitpack.io' }
}
dependencies {
    implementation 'com.github.pizpizi:libPVZ:v0.1.3' // Replace v0.1.0 with desired release tag or commit hash
}
```

## How to Use
### 1. Initialization
Initialize the `TextureBank` and `PamPlayer` by pointing them to your extracted asset directory (which should contain `IMAGES/`, `ATLASES/`, and a `Resources.json`, which is the decoded `RESOURCES.RTON`. Rton decoding may be added in the future.):
```java
FileHandle assetsFolder = Gdx.files.internal("assets");
TextureBank textures = new TextureBank("768", assetsFolder);
PamPlayer player = new PamPlayer(textures, assetsFolder);
```

### 2. Loading & Rendering Animations
You can render clips dynamically by name, or use `ClipRef` handles to eliminate string lookups in the render loop.

#### Dynamic Rendering:
```java
@Override
public void render() {
    stateTime += Gdx.graphics.getDeltaTime();
    // Required: updates background texture loading queue
    textures.update();
    batch.begin();
    // Render using PAM path and clip name directly
    player.draw(batch, "ZOMBIES/PEASANT/PEASANT.PAM", "idle", stateTime, x, y, true);
    batch.end();
}
```

#### `ClipRef` Rendering:
```java
// Preload synchronously during setup
player.loadSync("ZOMBIES/PEASANT/PEASANT.PAM");
// Obtain reusable performance handles (O(1) lookups during draw)
ClipRef walkClip = player.getClip("ZOMBIES/PEASANT/PEASANT.PAM", "walk");
@Override
public void render() {
    stateTime += Gdx.graphics.getDeltaTime();
    textures.update();
    batch.begin();
    player.draw(batch, walkClip, stateTime, x, y, true);
    batch.end();
}
```

### 3. Visibility Maps (Armor & Parts)
Some character parts (like armor or butter) are hidden by default. Use a `Map<String, Boolean>` to toggle specific part visibilities:
```java
Map<String, Boolean> visibilityMap = new HashMap<>();
visibilityMap.put("_zombie_egypt_armor2_states", true);
visibilityMap.put("zombie_armor_bucket_norm", true); // Show bucket armor
// Render with visibility map
player.draw(batch, walkClip, stateTime, x, y, true, visibilityMap);
// ...or with scale as well
player.draw(batch, walkClip, stateTime, x, y, 0.6f, 0.6f, true, visibilityMap);
```

### 4. Part Queries
`partBounds` reports where a named part (and its descendants) sits on a given frame, so gameplay can follow the art instead of guessing at it — advancing a walking character by its planted foot, hanging a projectile off a muzzle, or hit-testing a head:
```java
// Where is the foot right now? Canvas units, origin at the canvas centre, Y-down —
// the same space as bounds(). Scale it and add the draw position for world coordinates.
Rectangle foot = player.partBounds(walkClip, stateTime, "zombie_egypt_foot_inner_heel");
float footWorldX = x + (foot.x + foot.width / 2f) * scale;
```
Sampling a curve is cheaper in one pass, which is worth doing at load time rather than per frame:
```java
Rectangle[] perFrame = player.partBoundsByFrame(walkClip, "zombie_egypt_foot_inner_heel");
```
Entries are `null` where the part draws nothing on that frame. Both queries cover the same subtree `drawPart` renders.

### 5. Direct Region & Texture Retrieval
You can easily retrieve individual texture regions or full atlases directly from the `TextureBank`:
```java
// Fetch a specific sub-region by image resource ID
TextureRegion region = textures.region("IMAGE_ZOMBIE_EGYPT_BASIC_HEAD");
// Fetch a full texture atlas by atlas ID
TextureRegion fullAtlas = textures.atlas("ATLAS_ZOMBIE_EGYPT_BASIC");
```

> 💡 **Finding Assets:** To visually discover PAM file paths, clip names, and texture region IDs, you can use the [PvZ Asset Browser](https://github.com/pizpizi/pvz-asset-browser) tool.

## Running the Demo
The repository includes a runnable playground demo (`Demo.java`) located in the test sources.
1. Configure your local asset paths in `gradle.properties` (or pass them via command line):
   ```properties
   systemProp.pvz.assets=/path/to/Base Assets
   systemProp.pvz.pam=768/INITIAL/ZOMBIE/ZOMBIE_EGYPT_BASIC/ZOMBIE_EGYPT_BASIC.PAM
   ```
2. Run the Gradle task:
   ```bash
   ./gradlew runDemo
   ```

<img width="688" height="644" alt="libPVZ_Demo" src="https://github.com/user-attachments/assets/0d496925-54bb-4902-8f8e-3c431b3c632c" />

## Contributing
Bug reports and feature requests are welcome — please open an [issue](https://github.com/pizpizi/libPVZ/issues).

## License
This project is licensed under the [MIT License](LICENSE).

This is an unofficial, fan-made project and is not affiliated with, endorsed by, or associated with PopCap Games or Electronic Arts. No game assets are distributed with this library — you must provide your own extracted assets from a legally owned copy of the game.
