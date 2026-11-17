# Kraz

A scene rendering toolkit and library for Minecraft 1.21.1 (NeoForge). Build miniature worlds out of blocks,
block entities, entities, items and your own drawables; pose and animate them on a timeline; show them in any GUI
or in the world, plain or through a shader-pack style lighting pipeline; save them; and export antialiased PNGs
and GIFs with transparency. Comes with an in-game scene editor.

Client-side only. Package root: `me.methodial.kraz`.

## Modules

| Module | Mod id | What it is |
| --- | --- | --- |
| `core` | `kraz` | The library: scenes, animation, rendering, the shaded pipeline, saving, export, `/kraz capture`, `export`, `list`. No UI dependencies. This is what other mods depend on. |
| `editor` | `kraz_editor` | The scene editor and viewer (`/kraz edit`, `view`, `demo`), built with [Apothecary UI](../apothecary). Also holds the self test. |

Apothecary itself depends on `kraz` for all of its 3D drawing (`SceneView`, `ItemView`, `EntityView`,
`ShadedScene`), so the dependency chain is `kraz ← apothecary ← kraz_editor`.

## Quick start

```java
Scene scene = Scene.create();
scene.main().fill(new BlockPos(0, 0, 0), new BlockPos(4, 0, 4), Blocks.GRASS_BLOCK.defaultBlockState());
scene.main().setBlock(new BlockPos(2, 1, 2), Blocks.CHEST.defaultBlockState());
scene.fitCamera();

// In a vanilla screen
SceneWidget view = addRenderableWidget(new SceneWidget(x, y, 200, 150, scene));
// ...and in removed(): view.close();

// In an Apothecary UI
new SceneView(scene).interactive().style(s -> s.size(200, 150));

// Anywhere you have a GuiGraphics (tooltips, HUD, recipe viewers)
SceneCanvas canvas = new SceneCanvas(scene);          // keep it; close() it when done
canvas.draw(graphics, x, y, width, height, partialTick);

// Lit like a shader pack: sun shadows, sky and clouds, bloom, shaded water, glowing light sources
ShadedSceneCanvas shaded = new ShadedSceneCanvas(scene);
shaded.draw(graphics, x, y, width, height, partialTick);

// Straight to a file
SceneExporter.exportPng(scene, path, 2048, 2048, RenderSettings.HIGH_QUALITY);
```

`DemoScenes.island()` is a complete worked example; `/kraz demo` shows it in game.

## Concepts

| Thing | What it is |
| --- | --- |
| `Scene` | Parts + camera + environment + timeline. Holds no GPU resources. |
| `ScenePart` | A group of blocks that moves as one, with its own offset, rotation, pivot, scale, alpha and visibility. Animating a part never rebuilds geometry. |
| `SceneElement` | Something drawn every frame: `BlockElement` (free-moving block model), `ItemElement`, `EntityElement` (posable), `LiveEntityElement` (a real-world entity such as the player), or your own implementation. |
| `SceneCamera` | Orbit camera, orthographic (default) or perspective. |
| `SceneEnvironment` | Lighting (`FULL_BRIGHT` or flood-filled `COMPUTED`), time of day, biome tints. |
| `Timeline` | Keyframe tracks with easing, plus timed events with optional undo for scrubbing. |
| `SceneCanvas` | Renders a scene to a private antialiased framebuffer: into a GUI, a texture, or an image. |
| `ShadedSceneCanvas` | The same, through the shader pipeline (`render.shaded.ShadedScene`). |
| `SceneRenderer` | Low-level draw with explicit matrices, e.g. `drawInWorld(...)` from a `RenderLevelStageEvent`. |

## Posing and animation

Time is in ticks. Tracks bound to scene properties (camera, parts, named elements) are saved with the scene;
custom tracks and events are code.

```java
EntityElement zombie = part.add(new EntityElement(EntityType.ZOMBIE)).setPosition(2, 1, 2);
zombie.setBone("rightArm", -90, 0, 0).setHeadYaw(30).setLimbSwing(2).setLimbAmount(1).setPose(Pose.CROUCHING);

Timeline t = scene.timeline();
t.cameraYaw().key(0, -35f).key(200, 325f, Easing.IN_OUT_SINE);
t.offset(part).key(0, new Vector3f(0, 8, 0)).key(40, new Vector3f(), Easing.OUT_BOUNCE);
t.property(zombie, "bone/rightArm").key(0, new Vector3f()).key(20, new Vector3f(-90, 0, 0));
t.property(zombie, "limb_swing").key(0, 0f).key(40, 20f);     // walk on the spot
t.event(60, () -> part.setBlock(pos, lit), () -> part.setBlock(pos, unlit));
t.setLength(200).setLoop(true);
```

Bones are read from the entity's live model, so any mob (modded ones included) can be posed. Offsets are added on
top of the model's own animation. `SceneWidget` and `SceneView` play the timeline in real time; elsewhere call
`timeline.advance(deltaTicks)` or `seek(ticks)`.

## Scene editor

`/kraz edit [name]` (needs `kraz_editor` and Apothecary), or the *Open the scene editor* key (unbound by default).
`/kraz edit` without a name returns to the last session, including its unsaved changes and its world projection.

It is arranged like Blender (and Axiom), in Menuet's Ore UI look, with the scene filling the window:

- **Menu bar:** File, Edit, View, Add, Window, Help, then undo/redo, the brush, the latest message, the scene name and
  Save.
- **Panels are windows.** The Outliner, Properties and Timeline start docked left, right and bottom; Library, Show in
  world and Shortcuts open as floating windows.
  - Drag a docked panel's title bar to pull it out as a floating window.
  - Drop a floating window at the left, right or bottom edge to dock it there (the edge lights up first).
  - The buttons in a title bar float or dock the panel and hide it.
  - Drag the splitters between docks to resize them, and a floating window's corner to resize it.
  - Everything follows the pointer exactly.
  - The Window menu shows and hides panels, the tool bar, the navigation gizmo and the status bar, and has
    *Reset layout*.
- **UI scale:** View > Larger / Smaller / Reset, or Ctrl+= / Ctrl+− / Ctrl+0, on top of the game's GUI scale.
- **Tool bar:** down the left of the view: Select, Place, Erase, and the move, rotate and scale gizmos.
- **Navigation gizmo:** top right, as in Blender. Click an axis ball to look along it, drag it to orbit. Below it:
  drag-to-zoom and drag-to-pan handles, the lens toggle, frame selection and the grid.
- **Status bar:** mouse hints for the current tool on the left, and blocks, parts, elements, time and unsaved state
  on the right.
- **Small windows:** docks give way so the view always keeps its room, and the tool bar and navigation buttons wrap
  instead of running past it.

The arrangement, dock sizes, floating window positions and UI scale are saved to `config/kraz-editor.json`.

Working in it:

- **View:** right-drag orbits, middle-drag pans, the wheel zooms. Iso / Front / Side / Top (0 1 3 7), lens (5),
  F frames the selection, and a ground grid can be toggled.
- **Blocks:** Select (V), Place (B), Erase (E). Click to place one block, drag to paint (the stroke stays on the
  layer you started on), and Shift-drag to fill or clear a box. Each stroke is one undo step. Q copies the block
  under the cursor into the brush.
- **Library (Tab):** every block, item and entity in the game as icons, searchable. A block becomes the brush;
  an item or entity is added where the camera looks.
- **Gizmo:** Move (G), Rotate (R), Scale (S) the selected part or element. Ctrl snaps, Shift gives 1/16 steps.
- **Outliner:** parts and their elements. Click to select, double-click or F2 to rename (animation follows the
  rename), the eye or H to hide. The buttons below it add a part, the brush as a block, item or entity, duplicate
  (Ctrl+D) and delete.
- **Properties:** every value of the selection, including an entity's bones, walk cycle and pose. The diamond beside
  a value keys it: hollow means not animated, filled means animated, gold means keyed at this time. Shift-click
  removes the key.
- **Animation:** K keys the selection, Auto key keys every edit, the arrow keys step time, Space plays. In the dope
  sheet, drag keys to retime them and right-click a key or a track name to delete it; the easing menu sets the
  selected key's easing.
- **History and files:** undo/redo (Ctrl+Z / Ctrl+Y, 64 steps), Save (Ctrl+S), Open, New and PNG/GIF export.
  Closing or starting a new scene with unsaved changes asks first. Escape steps back one thing at a time: the text
  field, then the tool, the selection, and finally the editor.

## Showing scenes in the world

The globe button in the editor (or File > Show in world) opens the *Show in world* window. It places the scene being edited into the world,
live, so edits show up there as you make them:

- **Where:** at your feet, where you are looking, or at exact coordinates.
- **Anchor:** which point of the scene stands on that spot. *Stand* uses the bottom centre, *Centre* the middle,
  and *1:1 grid* keeps the scene's own coordinates so you can build from it block for block.
- **Facing:** yaw (with 90° buttons) and tilt.
- **Scale:** presets from 1/16 to 4, or an exact value.
- **Opacity:** below 100% the scene shows as a see-through ghost.
- **Animate:** plays the scene's animation in place.
- **Own lighting:** keeps the editor's lighting day and night, like a hologram; otherwise the scene is lit by the
  world around it.
- **Show outline** and **Visible** toggles.

*View in world* closes the editor and keeps the session; `/kraz edit` or the editor key brings it back.

From code:

```java
WorldProjection p = WorldProjections.add(new WorldProjection(scene, Vec3.atBottomCenterOf(pos)))
        .setScale(0.5f).setYaw(90).setAlpha(0.6f).setSceneLighting(true);
```

Projections are client-side and are dropped when you leave the world.

## Quality and performance

- Block geometry is baked per 16³ section into static vertex buffers on background threads and re-baked only
  for sections that changed. Sections outside the view frustum are skipped; translucent quads are re-sorted
  only when the camera moves.
- `RenderSettings`: MSAA sample count, 1×/2×/4× supersampling, and a chain of post effects.
  `DEFAULT` is 4× MSAA, `HIGH_QUALITY` is 8× MSAA on a 2× supersampled image, `FAST` is none.
- `canvas.setRedraw(Redraw.WHEN_CHANGED)` reuses the last image while nothing changed. Apothecary's `ItemView`
  uses this, so an inventory full of items costs one render per item rather than one per frame.
- Scenes use their own lightmap, so they look the same at night, underground and on the title screen.

Measured on the development machine (AMD integrated graphics) at 1280×720: the demo scene takes about
0.3–0.5 ms per frame without antialiasing, 0.8–1.2 ms at 4× MSAA and 4.5–6.5 ms at 8× MSAA + 2× supersampling;
a 96×96 terrain (36 sections) meshes in about 140–200 ms and orbits at about 1.5–2 ms per frame.

## Shaders

- **Post effects** are full-screen passes over the finished image: `PostEffects.FXAA`,
  `PostEffects.vignette(strength)`, or your own core shader wrapped in `ShaderEffect` (see its Javadoc).
- **The shaded pipeline** (`ShadedSceneCanvas`, moved here from Apothecary) renders a sun shadow map and the
  camera's view from the scene's baked geometry, then lights them in screen space: soft shadows, ambient
  occlusion, sky with sun, moon, stars and volumetric clouds, light shafts, shaded water and lava, point lights
  from light-emitting blocks, bloom, a filmic tonemap and FXAA. Tune it through `shading()`.

Block entity and entity renderers that use their own shaders work as they do in the world.

## Saving and loading

```java
SceneIO.write(scene, path);                    // gzip NBT: blocks, block entity data, elements, poses, camera, tracks
Scene scene = SceneIO.read(path);              // also reads vanilla structure-block .nbt files
Scene scene = SceneIO.read(resourceManager, ResourceLocation.fromNamespaceAndPath("mymod", "kraz/intro.nbt"));
Scene scene = SceneIO.capture(level, cornerA, cornerB, true);   // copy a box of a live world
SceneIO.loadInto(existingScene, tag);          // replace a displayed scene's contents in place
```

## Commands

```
/kraz capture <from> <to> <name> [entities]    -> <game dir>/kraz/scenes/<name>.nbt
/kraz export <name> png|gif [size]             -> <game dir>/kraz/exports/
/kraz list
/kraz project <name> [pos] [scale] [yaw]     show a saved scene in the world
/kraz project clear
/kraz edit [name]                              (kraz_editor; no name: back to the last session)
/kraz view <name>                              (kraz_editor)
/kraz demo                                     (kraz_editor)
```

## Compatibility

Checked by the self test (see Development):

- **Sodium 0.8.13.** It needs NeoForge 21.1.219 or newer, which is why the project builds against 21.1.266.
- **Create 6.0.10 / Flywheel 1.0.6.** Create's machines render inside scenes, animate, and save. Flywheel does
  not instance Kraz's private level, so Create falls back to its own block entity renderers there; the test
  checks that this is what happens.

## Installing

From the [releases](https://github.com/team-froglight/kraz/releases): `kraz-<version>.jar` is the library, and
`kraz-editor-<version>.jar` the editor. The editor also needs [Apothecary UI](https://github.com/team-froglight/apothecary),
which carries Kraz inside it.

## Using it as a dependency

`me.methodial:kraz` is published to GitHub Packages. GitHub Packages needs a token with `read:packages`, even for
public packages:

```groovy
repositories {
    maven {
        url = 'https://maven.pkg.github.com/team-froglight/kraz'
        credentials {
            username = providers.gradleProperty('gpr.user').orElse(providers.environmentVariable('GITHUB_ACTOR')).getOrElse('')
            password = providers.gradleProperty('gpr.key').orElse(providers.environmentVariable('GITHUB_TOKEN')).getOrElse('')
        }
    }
}
dependencies {
    implementation 'me.methodial:kraz:0.1.0+mc1.21.1'
    jarJar('me.methodial:kraz:0.1.0+mc1.21.1') { transitive = false }   // to ship it inside your mod
}
```

Declare `kraz` as a required client dependency in `neoforge.mods.toml`. For a sibling checkout without credentials,
`./gradlew :core:publishToMavenLocal` and add `mavenLocal()`.

## Building and releasing

Java 21. `./gradlew build` builds both jars into `core/build/libs` and `editor/build/libs`. The editor resolves
Apothecary from GitHub Packages (`gpr.user`/`gpr.key` in `~/.gradle/gradle.properties`), or from a sibling
checkout's `publishToMavenLocal`, which takes priority.

GitHub Actions builds every push to `main` and every pull request (`.github/workflows/ci.yml`) and attaches the jars
to the run. To release, bump `mod_version` in `gradle.properties` and run the **Publish** workflow. It publishes the
library to GitHub Packages, then builds the editor and creates the GitHub release with both jars. When a release also
needs a new Apothecary (which builds on Kraz), publish Apothecary after the library job, then re-run the release job.

## Limitations

- Block entities and entities need a loaded client world (their constructors need its registries). On the title
  screen they are skipped; blocks, items and block models still render.
- Iris/Oculus shader packs are not applied to scenes; the shaded pipeline is Kraz's own.
- Entities are posed, not simulated: they do not tick, walk or run AI.
- Scene block light is a flood fill, not vanilla's light engine: occlusion by slabs, stairs and similar partial
  shapes is approximated.
- GIF has 1-bit transparency, so translucent blocks over an empty background come out fully transparent. Set a
  scene background colour or export a PNG sequence when that matters.

## Development

- `./gradlew :editor:runClient` starts a dev client with Kraz, the editor and Apothecary.
  Apothecary comes from Maven local: run `./gradlew :core:publishToMavenLocal` here, then
  `./gradlew publishToMavenLocal` in `../apothecary`.
- `./gradlew :editor:runClientSelfTest` starts the game and drives every feature (rendering, antialiasing, post
  effects, the shaded pipeline, animation, posing, saving, export, capture, the viewer and the editor through
  their real input paths), writes `report.txt` and images to `build/selftest`, then quits. Add `-Pwith=sodium` or
  `-Pwith=create` to repeat it with those mods installed (results in `build/selftest-<mod>`). Keep the game
  window un-minimized while it runs: screenshot checks wait for a real framebuffer.
