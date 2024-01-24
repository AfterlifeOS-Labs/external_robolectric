package org.robolectric.shadows;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.os.Build.VERSION_CODES.JELLY_BEAN_MR1;
import static android.os.Build.VERSION_CODES.P;
import static java.util.Objects.requireNonNull;
import static org.robolectric.shadow.api.Shadow.extract;
import static org.robolectric.shadow.api.Shadow.invokeConstructor;
import static org.robolectric.shadows.ShadowLooper.shadowMainLooper;
import static org.robolectric.util.reflector.Reflector.reflector;

import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.BrightnessChangeEvent;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import com.google.auto.value.AutoBuilder;
import java.util.HashMap;
import java.util.List;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.Bootstrap;
import org.robolectric.android.internal.DisplayConfig;
import org.robolectric.annotation.HiddenApi;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.annotation.Resetter;
import org.robolectric.res.Qualifiers;
import org.robolectric.util.Consumer;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;

/**
 * For tests, display properties may be changed and devices may be added or removed
 * programmatically.
 */
@Implements(value = DisplayManager.class, minSdk = JELLY_BEAN_MR1, looseSignatures = true)
public class ShadowDisplayManager {

  @RealObject private DisplayManager realDisplayManager;

  private Context context;

  private static final String DEFAULT_DISPLAY_NAME = "Built-in screen";

  private static final HashMap<Integer, Boolean> displayIsNaturallyPortrait = new HashMap<>();

  @Resetter
  public static void reset() {
    displayIsNaturallyPortrait.clear();
  }

  @Implementation
  protected void __constructor__(Context context) {
    this.context = context;

    invokeConstructor(
        DisplayManager.class, realDisplayManager, ClassParameter.from(Context.class, context));
  }

  /**
   * Adds a simulated display and drain the main looper queue to ensure all the callbacks are
   * processed.
   *
   * @param qualifiersStr the {@link Qualifiers} string representing characteristics of the new
   *     display.
   * @return the new display's ID
   */
  public static int addDisplay(String qualifiersStr) {
    return addDisplay(qualifiersStr, DEFAULT_DISPLAY_NAME);
  }

  /**
   * Adds a simulated display and drain the main looper queue to ensure all the callbacks are
   * processed.
   *
   * @param qualifiersStr the {@link Qualifiers} string representing characteristics of the new
   *     display.
   * @param displayName the display name to use while creating the display
   * @return the new display's ID
   */
  public static int addDisplay(String qualifiersStr, String displayName) {
    int id =
        getShadowDisplayManagerGlobal()
            .addDisplay(createDisplayInfo(qualifiersStr, null, displayName));
    shadowMainLooper().idle();
    return id;
  }

  static IllegalStateException configureDefaultDisplayCallstack;

  /** internal only */
  public static void configureDefaultDisplay(
      Configuration configuration, DisplayMetrics displayMetrics) {
    ShadowDisplayManagerGlobal shadowDisplayManagerGlobal = getShadowDisplayManagerGlobal();
    if (DisplayManagerGlobal.getInstance().getDisplayIds().length == 0) {
      configureDefaultDisplayCallstack =
          new IllegalStateException("configureDefaultDisplay should only be called once");
    } else {
      configureDefaultDisplayCallstack.initCause(
          new IllegalStateException(
              "configureDefaultDisplay was called a second time",
              configureDefaultDisplayCallstack));
      throw configureDefaultDisplayCallstack;
    }

    shadowDisplayManagerGlobal.addDisplay(
        createDisplayInfo(
            configuration, displayMetrics, /* isNaturallyPortrait= */ true, DEFAULT_DISPLAY_NAME));
  }

  private static DisplayInfo createDisplayInfo(
      Configuration configuration,
      DisplayMetrics displayMetrics,
      boolean isNaturallyPortrait,
      String name) {
    int widthPx = (int) (configuration.screenWidthDp * displayMetrics.density);
    int heightPx = (int) (configuration.screenHeightDp * displayMetrics.density);

    DisplayInfo displayInfo = new DisplayInfo();
    displayInfo.name = name;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      displayInfo.uniqueId = "screen0";
    }
    displayInfo.appWidth = widthPx;
    displayInfo.appHeight = heightPx;
    fixNominalDimens(displayInfo);
    displayInfo.logicalWidth = widthPx;
    displayInfo.logicalHeight = heightPx;
    displayInfo.rotation =
        configuration.orientation == ORIENTATION_PORTRAIT
            ? (isNaturallyPortrait ? Surface.ROTATION_0 : Surface.ROTATION_90)
            : (isNaturallyPortrait ? Surface.ROTATION_90 : Surface.ROTATION_0);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      displayInfo.modeId = 0;
      displayInfo.defaultModeId = 0;
      displayInfo.supportedModes = new Display.Mode[] {new Display.Mode(0, widthPx, heightPx, 60)};
    }
    displayInfo.logicalDensityDpi = displayMetrics.densityDpi;
    displayInfo.physicalXDpi = displayMetrics.densityDpi;
    displayInfo.physicalYDpi = displayMetrics.densityDpi;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      displayInfo.state = Display.STATE_ON;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      displayInfo.getAppMetrics(displayMetrics);
    }

    return displayInfo;
  }

  private static DisplayInfo createDisplayInfo(String qualifiersStr, @Nullable Integer displayId) {
    return createDisplayInfo(qualifiersStr, displayId, DEFAULT_DISPLAY_NAME);
  }

  private static DisplayInfo createDisplayInfo(
      String qualifiersStr, @Nullable Integer displayId, String name) {
    DisplayInfo baseDisplayInfo =
        displayId != null ? DisplayManagerGlobal.getInstance().getDisplayInfo(displayId) : null;
    Configuration configuration = new Configuration();
    DisplayMetrics displayMetrics = new DisplayMetrics();

    boolean isNaturallyPortrait =
        requireNonNull(displayIsNaturallyPortrait.getOrDefault(displayId, true));
    if (qualifiersStr.startsWith("+") && baseDisplayInfo != null) {
      configuration.orientation =
          isRotated(baseDisplayInfo.rotation)
              ? (isNaturallyPortrait ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT)
              : (isNaturallyPortrait ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE);
      configuration.screenWidthDp =
          baseDisplayInfo.logicalWidth
              * DisplayMetrics.DENSITY_DEFAULT
              / baseDisplayInfo.logicalDensityDpi;
      configuration.screenHeightDp =
          baseDisplayInfo.logicalHeight
              * DisplayMetrics.DENSITY_DEFAULT
              / baseDisplayInfo.logicalDensityDpi;
      configuration.densityDpi = baseDisplayInfo.logicalDensityDpi;
      displayMetrics.densityDpi = baseDisplayInfo.logicalDensityDpi;
      displayMetrics.density =
          baseDisplayInfo.logicalDensityDpi * DisplayMetrics.DENSITY_DEFAULT_SCALE;
    }

    Bootstrap.applyQualifiers(
        qualifiersStr, RuntimeEnvironment.getApiLevel(), configuration, displayMetrics);

    return createDisplayInfo(configuration, displayMetrics, isNaturallyPortrait, name);
  }

  private static boolean isRotated(int rotation) {
    return rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
  }

  private static void fixNominalDimens(DisplayInfo displayInfo) {
    int smallest = Math.min(displayInfo.appWidth, displayInfo.appHeight);
    int largest = Math.max(displayInfo.appWidth, displayInfo.appHeight);

    displayInfo.smallestNominalAppWidth = smallest;
    displayInfo.smallestNominalAppHeight = smallest;
    displayInfo.largestNominalAppWidth = largest;
    displayInfo.largestNominalAppHeight = largest;
  }

  /**
   * Changes properties of a simulated display. If {@param qualifiersStr} starts with a plus ('+')
   * sign, the display's previous configuration is modified with the given qualifiers; otherwise
   * defaults are applied as described <a
   * href="http://robolectric.org/device-configuration/">here</a>.
   *
   * <p>Idles the main looper to ensure all listeners are notified.
   *
   * @param displayId the display id to change
   * @param qualifiersStr the {@link Qualifiers} string representing characteristics of the new
   *     display
   */
  public static void changeDisplay(int displayId, String qualifiersStr) {
    DisplayInfo displayInfo = createDisplayInfo(qualifiersStr, displayId);
    getShadowDisplayManagerGlobal().changeDisplay(displayId, displayInfo);
    shadowMainLooper().idle();
  }

  /**
   * Changes the display to be naturally portrait or landscape. This will ensure that the rotation
   * is configured consistently with orientation when the orientation is configured by {@link
   * #changeDisplay}, e.g. if the display is naturally portrait and the orientation is configured as
   * landscape the rotation will be set to {@link Surface#ROTATION_90}.
   */
  public static void setNaturallyPortrait(int displayId, boolean isNaturallyPortrait) {
    displayIsNaturallyPortrait.put(displayId, isNaturallyPortrait);
    changeDisplay(
        displayId,
        config -> {
          boolean isRotated = isRotated(config.rotation);
          boolean isPortrait = config.logicalHeight > config.logicalWidth;
          if ((isNaturallyPortrait ^ isPortrait) != isRotated) {
            config.rotation =
                (isNaturallyPortrait ^ isPortrait) ? Surface.ROTATION_90 : Surface.ROTATION_0;
          }
        });
    shadowMainLooper().idle();
  }

  /**
   * Sets supported modes to the specified display with ID {@code displayId}.
   *
   * <p>Idles the main looper to ensure all listeners are notified.
   *
   * @param displayId the display id to change
   * @param supportedModes the display's supported modes
   */
  public static void setSupportedModes(int displayId, Display.Mode... supportedModes) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      throw new UnsupportedOperationException("multiple display modes not supported before M");
    }
    DisplayInfo displayInfo = DisplayManagerGlobal.getInstance().getDisplayInfo(displayId);
    displayInfo.supportedModes = supportedModes;
    getShadowDisplayManagerGlobal().changeDisplay(displayId, displayInfo);
    shadowMainLooper().idle();
  }

  /**
   * Changes properties of a simulated display. The original properties will be passed to the
   * {@param consumer}, which may modify them in place. The display will be updated with the new
   * properties.
   *
   * @param displayId the display id to change
   * @param consumer a function which modifies the display properties
   */
  static void changeDisplay(int displayId, Consumer<DisplayConfig> consumer) {
    DisplayInfo displayInfo = DisplayManagerGlobal.getInstance().getDisplayInfo(displayId);
    if (displayInfo != null) {
      DisplayConfig displayConfig = new DisplayConfig(displayInfo);
      consumer.accept(displayConfig);
      displayConfig.copyTo(displayInfo);
      fixNominalDimens(displayInfo);
    }

    getShadowDisplayManagerGlobal().changeDisplay(displayId, displayInfo);
  }

  /**
   * Removes a simulated display and idles the main looper to ensure all listeners are notified.
   *
   * @param displayId the display id to remove
   */
  public static void removeDisplay(int displayId) {
    getShadowDisplayManagerGlobal().removeDisplay(displayId);
    shadowMainLooper().idle();
  }

  /**
   * Returns the current display saturation level set via {@link
   * android.hardware.display.DisplayManager#setSaturationLevel(float)}.
   */
  public float getSaturationLevel() {
    if (RuntimeEnvironment.getApiLevel() >= Build.VERSION_CODES.Q) {
      ShadowColorDisplayManager shadowCdm =
          extract(context.getSystemService(Context.COLOR_DISPLAY_SERVICE));
      return shadowCdm.getSaturationLevel() / 100f;
    }
    return getShadowDisplayManagerGlobal().getSaturationLevel();
  }

  /**
   * Sets the current display saturation level.
   *
   * <p>This is a workaround for tests which cannot use the relevant hidden {@link
   * android.annotation.SystemApi}, {@link
   * android.hardware.display.DisplayManager#setSaturationLevel(float)}.
   */
  @Implementation(minSdk = P)
  public void setSaturationLevel(float level) {
    reflector(DisplayManagerReflector.class, realDisplayManager).setSaturationLevel(level);
  }

  @Implementation(minSdk = P)
  @HiddenApi
  protected void setBrightnessConfiguration(Object config) {
    setBrightnessConfigurationForUser(config, 0, context.getPackageName());
  }

  @Implementation(minSdk = P)
  @HiddenApi
  protected void setBrightnessConfigurationForUser(
      Object config, Object userId, Object packageName) {
    getShadowDisplayManagerGlobal().setBrightnessConfigurationForUser(config, userId, packageName);
  }

  /** Set the default brightness configuration for this device. */
  public static void setDefaultBrightnessConfiguration(Object config) {
    getShadowDisplayManagerGlobal().setDefaultBrightnessConfiguration(config);
  }

  /** Set the slider events the system has seen. */
  public static void setBrightnessEvents(List<BrightnessChangeEvent> events) {
    getShadowDisplayManagerGlobal().setBrightnessEvents(events);
  }

  private static ShadowDisplayManagerGlobal getShadowDisplayManagerGlobal() {
    if (Build.VERSION.SDK_INT < JELLY_BEAN_MR1) {
      throw new UnsupportedOperationException("multiple displays not supported in Jelly Bean");
    }

    return extract(DisplayManagerGlobal.getInstance());
  }

  @RequiresApi(api = Build.VERSION_CODES.M)
  static Display.Mode displayModeOf(int modeId, int width, int height, float refreshRate) {
    return new Display.Mode(modeId, width, height, refreshRate);
  }

  /** Builder class for {@link Display.Mode} */
  @RequiresApi(api = Build.VERSION_CODES.M)
  @AutoBuilder(callMethod = "displayModeOf")
  public abstract static class ModeBuilder {
    public static ModeBuilder modeBuilder(int modeId) {
      return new AutoBuilder_ShadowDisplayManager_ModeBuilder().setModeId(modeId);
    }

    abstract ModeBuilder setModeId(int modeId);

    public abstract ModeBuilder setWidth(int width);

    public abstract ModeBuilder setHeight(int height);

    public abstract ModeBuilder setRefreshRate(float refreshRate);

    public abstract Display.Mode build();
  }

  @ForType(DisplayManager.class)
  interface DisplayManagerReflector {

    @Direct
    void setSaturationLevel(float level);
  }
}
