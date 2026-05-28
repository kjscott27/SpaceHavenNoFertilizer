package com.kjscott27.NoFertilizer;

import fi.bugbyte.framework.Settings;

import java.lang.reflect.Field;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;

@Aspect
public class FertilizerUIAspect {

    /**
     * Cached screen-space position of the grow rate label, updated every time
     * updateGrowRate() runs (~every 2 seconds).  Used for click-region detection.
     */
    private static volatile float cachedGrowRateX = 0f;
    private static volatile float cachedGrowRateY = 0f;

    // -------------------------------------------------------------------------
    // 1. Append the toggle indicator to the grow-rate label text
    //
    //    GrowBedSettings.updateGrowRate() builds the string:
    //      "Growth Rate: 100.0 %"
    //    and stores it in the private field `growRate`.
    //    We reflect into the GrowBedSettings instance after the method runs and
    //    append "  [No Fert: ON/OFF]" so it reads:
    //      "Growth Rate: 100.0 %  [No Fert: OFF]"
    //    We also cache growRateX/growRateY for the click-region check below.
    // -------------------------------------------------------------------------

    @Pointcut("execution(void fi.bugbyte.spacehaven.gui.WorldElementInfos$GrowBedSettings.updateGrowRate())")
    public void updateGrowRate() {}

    @After("updateGrowRate()")
    public void afterUpdateGrowRate(JoinPoint joinPoint) {
        Object target = joinPoint.getThis();
        if (target == null) return;

        try {
            Class<?> cls = target.getClass();

            Field growRateField = cls.getDeclaredField("growRate");
            growRateField.setAccessible(true);
            String growRate = (String) growRateField.get(target);

            Field growRateXField = cls.getDeclaredField("growRateX");
            growRateXField.setAccessible(true);
            cachedGrowRateX = (float) growRateXField.get(target);

            Field growRateYField = cls.getDeclaredField("growRateY");
            growRateYField.setAccessible(true);
            cachedGrowRateY = (float) growRateYField.get(target);

            if (growRate != null) {
                String status = FertilizerCoreAspect.fertilizerFreeMode ? "ON" : "OFF";
                growRateField.set(target, growRate + "  [No Fert: " + status + "]");
            }
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // 2. Handle clicks on the "[No Fert: ...]" portion of the grow-rate label
    //
    //    The label is drawn at (cachedGrowRateX, cachedGrowRateY).
    //    "Growth Rate: xx.x %  " is approximately 180px wide at uiScale = 1.
    //    The "[No Fert: OFF]" / "[No Fert: ON]" text is ~130px wide at uiScale = 1.
    //    The click region covers that right-hand portion.
    //    LibGDX y is baseline-up, so the hit box spans -18..+4 pixels around
    //    the baseline y.
    //
    //    If the click lands inside that region the toggle flips and we return
    //    false (click consumed).  Otherwise the original touchDown logic runs.
    // -------------------------------------------------------------------------

    @Pointcut("execution(boolean fi.bugbyte.spacehaven.gui.WorldElementInfos$GrowBedSettings.touchDown(float, float, int, int)) && args(x, y, pointer, button)")
    public void growBedTouchDown(float x, float y, int pointer, int button) {}

    @Around("growBedTouchDown(x, y, pointer, button)")
    public Object aroundTouchDown(ProceedingJoinPoint pjp, float x, float y, int pointer, int button) throws Throwable {
        float scale = Settings.uiScale;

        float hitX1 = cachedGrowRateX + 180f * scale;
        float hitX2 = hitX1 + 130f * scale;
        float hitY1 = cachedGrowRateY - 18f * scale;
        float hitY2 = cachedGrowRateY + 4f * scale;

        if (x >= hitX1 && x <= hitX2 && y >= hitY1 && y <= hitY2) {
            FertilizerCoreAspect.fertilizerFreeMode = !FertilizerCoreAspect.fertilizerFreeMode;
            return false;
        }

        return pjp.proceed();
    }
}
