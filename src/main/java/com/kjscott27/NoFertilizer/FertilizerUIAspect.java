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

    // Screen-space position of the grow rate label, updated each time updateGrowRate() runs.
    // Used to define the click region for the toggle.
    private static volatile float cachedGrowRateX = 0f;
    private static volatile float cachedGrowRateY = 0f;

    // Intercepts GrowBedSettings.updateGrowRate() after it builds the "Growth Rate: xx.x %"
    // label. Appends "  [No Fert: ON/OFF]" to the string via reflection and caches the
    // label's screen coordinates for the touch handler below.

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

    // Intercepts GrowBedSettings.touchDown(). The "Growth Rate" text is ~180px wide at
    // uiScale=1; the appended "[No Fert: ...]" badge covers the next ~130px. A click
    // anywhere in that badge region flips the toggle. LibGDX y is baseline-up, so the
    // hit box spans -18..+4px around the label baseline.

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
