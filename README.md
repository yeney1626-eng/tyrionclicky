# ClickyCursor

A minimal, source-available clone of the "Clicky: Mouse Cursor" concept, built
for keypad Android phones like the Duoqin/Qin F22 Pro. It draws an arrow
pointer on screen, moves it with the hardware D-pad, and "clicks" wherever the
pointer is when you press the center/OK key (long-press = long-click).

## How it works

- `CursorAccessibilityService` is an `AccessibilityService` with
  `canRequestFilterKeyEvents="true"`, which lets it intercept D-pad key events
  system-wide before the foreground app sees them.
- It draws the cursor using a `TYPE_ACCESSIBILITY_OVERLAY` window, which is the
  one overlay type accessibility services are allowed to add without the
  separate "draw over other apps" permission.
- Clicks are performed with `dispatchGesture()`, simulating a real tap at the
  cursor's current coordinates.
- It watches for the on-screen/software keyboard appearing and hides/pauses
  the pointer while you're typing, so it doesn't get in the way of the T9 input.

## Build it yourself

You'll need **Android Studio** (free, from developer.android.com) — that's
the easiest way to get the correct SDK/build tools without wrestling with
command-line setup.

1. Open Android Studio → **Open** → select this `ClickyCursor` folder.
2. Let it sync (it will download the Android Gradle Plugin and SDK platform
   34 automatically the first time — needs an internet connection).
3. Connect your F22 Pro via USB with **USB debugging** enabled
   (Settings → About phone → tap Build number 7 times → Developer options →
   USB debugging), or export a signed APK instead:
   **Build → Generate Signed Bundle / APK → APK**.
4. Install: either hit the green Run ▶ button with the phone connected, or
   copy the generated `app-release.apk` to the phone and open it to sideload
   (you'll need to allow "install unknown apps" for whatever file manager you
   use to open it).
5. On the phone, open the ClickyCursor app once, tap
   **"Open Accessibility Settings"**, find ClickyCursor in the list, and turn
   it ON. Accept the permission prompt (it needs "view/control screen" to draw
   the cursor and perform taps).

## Using it

- D-pad **Up/Down/Left/Right** → move the pointer (holding a direction speeds
  it up).
- **Center/OK key** → tap where the pointer is.
- **Hold** center/OK → long-press/long-click where the pointer is.
- Pointer auto-hides while the T9/software keyboard is open.

## Things you may want to tweak

- `baseStep` / acceleration curve in `handleDirection()` — how fast the
  cursor moves.
- The cursor graphic — swap `res/drawable/cursor_arrow.xml` for your own icon.
- Add drag support (press-and-hold-then-move-then-release to select text or
  drag sliders) by extending the long-press branch to start a
  `GestureDescription.StrokeDescription` that follows subsequent D-pad moves
  instead of ending immediately.
- A boss-key / toggle to fully hide the cursor without disabling the service —
  e.g. bind a long-press on a specific number key to flip a "hidden" flag.

## Known limitations of this starter version

- No settings screen for cursor speed/appearance yet (hardcoded).
- No drag-and-drop / text-selection gesture yet, only tap and long-tap.
- Not published anywhere — this is a local project you build and sideload
  yourself, so there's no Play Protect / store review on it. Only install it
  from your own build.
