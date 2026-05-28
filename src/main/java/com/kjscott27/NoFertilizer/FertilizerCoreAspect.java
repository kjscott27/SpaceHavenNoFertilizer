package com.kjscott27.NoFertilizer;

import com.badlogic.gdx.utils.Array;
import fi.bugbyte.spacehaven.stuff.Production;
import fi.bugbyte.spacehaven.world.elements.WorldObject;

import java.lang.reflect.Field;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;

@Aspect
public class FertilizerCoreAspect {

    public static final int FERTILIZER_ELEMENT_ID = 2475;

    // global toggle, toggled by clicking the grow rate label in the UI (see FertilizerUIAspect)
    public static volatile boolean fertilizerFreeMode = false;


    /*  consumeNeeds() checks inv.canConsume(needs) — if ALL needs are present
        it consumes them and sets canGrow = true.  When the toggle is ON we
        temporarily remove the fertilizer Need from the list so the check only
        requires water, then restore it immediately afterwards so game state is
        not permanently modified. */

    @Pointcut("execution(void fi.bugbyte.spacehaven.world.elements.WorldObject$GrowPlace.consumeNeeds(fi.bugbyte.spacehaven.world.elements.Storage$Inventory)) && this(growPlace)")
    public void consumeNeeds(WorldObject.GrowPlace growPlace) {}

    @Around("consumeNeeds(growPlace)")
    public Object aroundConsumeNeeds(ProceedingJoinPoint pjp, WorldObject.GrowPlace growPlace) throws Throwable {
        if (!fertilizerFreeMode) {
            return pjp.proceed();
        }

        Production.Need removedNeed = null;
        Field currentField = null;
        Field needsField = null;

        try {
            // GrowPlace.current is of type GrowPlace.GrowStage (private inner class)
            currentField = growPlace.getClass().getDeclaredField("current");
            currentField.setAccessible(true);
            Object currentStage = currentField.get(growPlace);

            if (currentStage != null) {
                needsField = currentStage.getClass().getDeclaredField("needs");
                needsField.setAccessible(true);

                @SuppressWarnings("unchecked")
                Array<Production.Need> needs = (Array<Production.Need>) needsField.get(currentStage);

                if (needs != null) {
                    for (int i = 0; i < needs.size; i++) {
                        if (needs.get(i).element == FERTILIZER_ELEMENT_ID) {
                            removedNeed = needs.removeIndex(i);
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Reflection failed — fall back to vanilla behaviour so nothing breaks
            return pjp.proceed();
        }

        try {
            return pjp.proceed();
        } finally {
            // Always restore the Need so the crop's stage definition stays intact
            if (removedNeed != null && currentField != null && needsField != null) {
                try {
                    Object currentStage = currentField.get(growPlace);
                    if (currentStage != null) {
                        @SuppressWarnings("unchecked")
                        Array<Production.Need> needs = (Array<Production.Need>) needsField.get(currentStage);
                        if (needs != null) {
                            needs.add(removedNeed);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
    }


    /*  Halving the upgrade value multiplier is equivalent to halving the entire
        final growth rate, because it is the last factor applied.  This preserves
        the full contribution of light, CO2, tending skill, and researched upgrades.
        Final formula with toggle ON:
        effectiveRate = (light * co2 * skill * researchUpgrade) * 0.5 */

    @Pointcut("call(float fi.bugbyte.spacehaven.world.World.getUpgradeValue(fi.bugbyte.spacehaven.stuff.ResearchUnlocks$UpgradeType, fi.bugbyte.spacehaven.stuff.FactionUtils$FactionSide)) && withincode(void fi.bugbyte.spacehaven.world.elements.WorldObject$GrowPlace.update(float))")
    public void growPlaceUpgradeValue() {}

    @Around("growPlaceUpgradeValue()")
    public Object aroundGrowPlaceUpgradeValue(ProceedingJoinPoint pjp) throws Throwable {
        float result = (Float) pjp.proceed();
        if (fertilizerFreeMode) {
            return result * 0.5f;
        }
        return result;
    }
}
