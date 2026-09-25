# Onboarding Tutorial Design

## Overview

First-time user tutorial for Polaris Safety app. Shows a welcome screen with animated icon, then guides users through key features (scanning and guard dashboard) with overlay-based step-by-step introduction.

## Architecture

### Component Diagram

```
OnboardingActivity (launcher on first launch)
  ├── WelcomeView (step 0: icon animation + welcome text + enter button)
  ├── OnboardingOverlayView (custom View: dark overlay + highlight cutout + guide icon + trail)
  └── TutorialStep enum (WELCOME → SCAN_INTRO → GUARD_INTRO → DONE)

MainActivity
  └── onCreate: check isTutorialDone() → if false, launch OnboardingActivity

Prefs
  └── KEY_TUTORIAL_DONE = "tutorial_done"
  └── isTutorialDone() / setTutorialDone()
```

### Flow

1. App launches → `MainActivity.onCreate()` checks `prefs.isTutorialDone()`
2. If false → start `OnboardingActivity` (override transition)
3. Welcome screen plays animation sequence
4. User taps "Enter" → overlay view shows scan intro
5. User taps "Next" → overlay shows guard intro with swipe guide
6. User swipes right (or 5s timeout) → tutorial done
7. `prefs.setTutorialDone(true)` → finish OnboardingActivity → back to MainActivity

## Detailed Design

### OnboardingActivity

- **Theme**: `Theme.Polaris` (no action bar, fullscreen)
- **Flags**: `FLAG_FULLSCREEN | FLAG_LAYOUT_NO_LIMITS`
- **Content**: FrameLayout containing:
  - Background color view (solid color)
  - WelcomeView (step 0 only)
  - OnboardingOverlayView (steps 1-2)

### Step 0: Welcome Screen

**Layout**: Solid color background (`#1A237E` deep blue)

**Elements**:
- App icon (80dp, `ic_launcher_foreground` vector) — starts centered
- Welcome title text — "欢迎使用 Polaris" (localized)
- Subtitle text — "守护你的设备安全" (localized)
- Enter button — MaterialButton "开始体验"

**Animation Sequence** (total ~3s):

| Time | Animation | Easing |
|------|-----------|--------|
| 0ms | Icon scale 0→1 (overshoot 1.5) | `OvershootInterpolator(1.5f)` |
| 600ms | Pause | — |
| 1000ms | Icon translates Y to screen top 1/3 | `FastOutSlowInInterpolator` |
| 1800ms | Title fades in + translates Y 20→0 | `DecelerateInterpolator` |
| 2000ms | Subtitle fades in (same) | `DecelerateInterpolator` |
| 2200ms | Button fades in + translates Y 30→0 | `DecelerateInterpolator` |

### Step 1: Scan Introduction

**Overlay**: `OnboardingOverlayView`
- Full screen dark overlay (`#CC000000`)
- Highlight cutout: mode cards region (`modeList` bounds + 16dp padding, 20dp corner radius)

**Guide icon**: 24dp star icon slides in from left edge to highlight region's left side
- Path: `(screenWidth * 0.1, screenHeight * 0.5)` → `(highlight.left - 32dp, highlight.centerY)`
- Duration: 800ms, `FastOutSlowInInterpolator`
- Trail: 5 ghost copies, each delayed 50ms, alpha 0.6→0.12, scale 1.0→0.8

**Text panel**: Appears to the right of guide icon
- Title: "全面扫描" (localized)
- Body: "一键扫描所有应用，检测恶意程序" (localized)
- Animation: fade in + translate X 20→0, 400ms, delayed 600ms

**Button**: "下一步" at bottom center, fade in delayed 800ms

### Step 2: Guard Introduction

**Overlay**: Highlight shifts to left portion of screen (main content area, ~70% width)

**Guide icon**: Star starts at center of highlight, slides rightward
- Path: `(highlight.centerX, highlight.centerY)` → `(screenWidth * 0.85, screenHeight * 0.5)`
- Duration: 1200ms, `FastOutSlowInInterpolator`
- Trail: same as step 1

**Text prompt**: "向右滑动打开守护面板" at bottom, fades in after icon stops

**Interaction**:
- User can swipe right on the screen → opens DrawerLayout → tutorial auto-completes
- Auto-complete after 5 seconds if no swipe detected
- On auto-complete, simulate drawer open with animation

### Step 3: Completion

- Overlay fades out (300ms)
- `prefs.setTutorialDone(true)`
- `finish()` with override pending transition (fade)

### OnboardingOverlayView (Custom View)

```java
public class OnboardingOverlayView extends View {
    // Paint for overlay
    private Paint overlayPaint;    // #CC000000
    private Paint clearPaint;      // PorterDuff.CLEAR for cutout

    // Highlight region
    private RectF highlightRect;
    private float highlightRadius;

    // Guide icon
    private Bitmap starBitmap;
    private float iconX, iconY;
    private float iconScale;
    private float iconAlpha;

    // Trail (ghost copies)
    private List<TrailGhost> ghosts;  // max 5

    // Methods
    void setHighlight(RectF rect, float radius);
    void animateIconTo(float targetX, float targetY, long duration, Interpolator interp);
    void drawTrail(Canvas canvas);
}
```

**Rendering approach**:
- Draw dark overlay on offscreen buffer
- Use `PorterDuff.Mode.CLEAR` to punch out the highlight cutout
- Draw result to canvas
- Draw guide icon + trail on top

### Prefs Changes

```java
// Add to Prefs.java
private static final String KEY_TUTORIAL_DONE = "tutorial_done";

public boolean isTutorialDone() {
    return sp.getBoolean(KEY_TUTORIAL_DONE, false);
}

public void setTutorialDone() {
    sp.edit().putBoolean(KEY_TUTORIAL_DONE, true).apply();
}
```

### AndroidManifest Changes

```xml
<!-- Change OnboardingActivity to launcher -->
<activity
    android:name=".OnboardingActivity"
    android:exported="true"
    android:theme="@style/Theme.Polaris">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>

<!-- Remove launcher from MainActivity -->
<activity android:name=".MainActivity" />
```

### String Resources

| Key | Chinese | English | Japanese |
|-----|---------|---------|----------|
| `tutorial_welcome_title` | 欢迎使用 Polaris | Welcome to Polaris | Polarisへようこそ |
| `tutorial_welcome_subtitle` | 守护你的设备安全 | Protecting your device | デバイスを守ります |
| `tutorial_enter` | 开始体验 | Get Started | 始める |
| `tutorial_scan_title` | 全面扫描 | Full Scan | 全面スキャン |
| `tutorial_scan_desc` | 一键扫描所有应用，检测恶意程序 | Scan all apps to detect malware | すべてのアプリをスキャンして悪意のあるプログラムを検出 |
| `tutorial_next` | 下一步 | Next | 次へ |
| `tutorial_guard_prompt` | 向右滑动打开守护面板 | Swipe right to open Guard panel | 右にスワイプしてガードパネルを開く |
| `tutorial_guard_desc` | 守护面板实时监控设备安全 | Guard panel monitors device security | ガードパネルはデバイスのセキュリティをリアルタイムで監視 |

## Files to Create/Modify

| File | Action | Description |
|------|--------|-------------|
| `OnboardingActivity.java` | CREATE | Tutorial orchestrator |
| `activity_onboarding.xml` | CREATE | Welcome page layout |
| `OnboardingOverlayView.java` | CREATE | Custom overlay + highlight + guide icon |
| `Prefs.java` | MODIFY | Add KEY_TUTORIAL_DONE + getter/setter |
| `MainActivity.java` | MODIFY | Check tutorial state in onCreate |
| `AndroidManifest.xml` | MODIFY | Swap launcher intent |
| `strings.xml` (zh/en/ja) | MODIFY | Add tutorial strings |

## Testing

1. Fresh install → OnboardingActivity launches automatically
2. Welcome animation plays smoothly (no jank)
3. Tap "开始体验" → overlay appears with scan intro
4. Tap "下一步" → guard intro appears
5. Swipe right → drawer opens → tutorial completes
6. Kill app → reopen → tutorial does NOT show again
7. Check all strings display correctly in zh/en/ja
8. Test on API 24 (minSdk) and API 34 (latest)
