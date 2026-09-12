LifeStealLives 3.9.0 - simplified rebuild
==========================================

IMPORTANT - please read this part first
----------------------------------------
I don't have a Java compiler or internet access in this chat environment,
so I could NOT build this into a .jar myself, and I could not test it
in-game. What's in this zip is real, complete source code -- not
pseudocode -- but it needs to be compiled before it'll run on your server.
I'm sorry for implying earlier that the old jar's behavior was verified
when it wasn't; this time I'm being upfront about that limit.

How to build it (pick one)
----------------------------------------
OPTION A - No installs at all, build it in the cloud with GitHub (recommended
if you don't already have Java/Maven set up):
  1. Make a free account at github.com if you don't have one.
  2. Create a new repository (any name, e.g. "lifesteallives").
  3. Unzip this package, then upload EVERYTHING in it to that repo,
     keeping the folder structure exactly as-is (pom.xml at the root,
     the src/ folder, and the .github/ folder all need to end up at the
     top level of the repo -- GitHub's "Add file > Upload files" supports
     dragging whole folders in most browsers, or use GitHub Desktop).
  4. Go to the "Actions" tab of your repo. A workflow called
     "Build LifeStealLives" should run automatically. Wait for the green
     checkmark (~1 minute).
  5. Click into that workflow run, scroll to "Artifacts", and download
     "LifeStealLives-jar" -- that's your compiled plugin jar (zipped,
     unzip it once to get the actual .jar).

OPTION B - If you already have Java + Maven installed locally:
  1. Unzip this wherever you like.
  2. In that folder, run:  mvn package
  3. The finished jar appears in target/LifeStealLives-3.9.0.jar

OPTION C - IntelliJ IDEA (Community Edition, free):
  Open this folder as a Maven project, let it sync, then run `mvn package`
  in its built-in terminal, or use Build > Build Artifacts.

What changed vs the old v3.8 jar
----------------------------------------
1. POTION DURATION FEATURE: completely removed. There is no code left
   anywhere touching potion durations based on lives. Clean slate.

2. GOLDEN APPLE / ABSORPTION FLASH: fixed at the root cause. The old
   version let the vanilla absorption effect apply first and corrected it
   afterward -- that's what caused the visible "2 hearts then drops to 1"
   flash. The new version intercepts the effect before it's ever applied
   and sets the final absorption amount directly, once. This applies to
   ANY source of absorption (golden apples, enchanted golden apples,
   totems, other plugins), not just eating -- per your "same goes for
   other" note.

   The amounts still come from config.yml's absorption-points map:
     3 lives -> 4.0 (2 hearts)
     2 lives -> 2.0 (1 heart)
     1 life  -> 1.0 (half heart)
     0 lives -> 0.0
   Change these numbers if you want different amounts.

3. NAMETAG vs TAB LIST MISMATCH: the old code likely set these two things
   through two different code paths, which is exactly the kind of setup
   that can drift out of sync. The new version uses exactly ONE mechanism
   -- a scoreboard team suffix -- which drives both the nametag above a
   player's head AND their tab list entry from the same call. They cannot
   show different things anymore because there's only one place that sets
   either of them.

Why the texture pack was showing squares
----------------------------------------
The plugin code and the resource pack both reference the same font key
(lifesteallives:hearts) and the same three characters, so that part lines
up correctly. Squares (tofu) almost always mean the pack itself wasn't
actually active on the client. Things to check:
  - If client-side: Options > Resource Packs > is it in the "Selected"
    column, not just "Available"? It has to be moved over AND you exit
    the menu with it selected.
  - If server-enforced: did you actually click "Yes" on the resource pack
    prompt that pops up when joining? Declining it silently leaves you
    with tofu.
  - If server-enforced: does resource-pack-sha1 in server.properties
    exactly match the zip you're hosting? A mismatch makes some server
    versions reject the pack outright.
  - Try it single-player first (drop the zip in .minecraft/resourcepacks,
    select it, then use /lives in a world) to rule out server-side
    hosting/URL issues before troubleshooting the server-enforced path.

Files in this zip
----------------------------------------
pom.xml                                            - Maven build config
src/main/java/.../LifeStealLives.java              - full plugin source
src/main/resources/plugin.yml                      - unchanged commands/perms, version bumped
src/main/resources/config.yml                      - potion-duration keys removed
