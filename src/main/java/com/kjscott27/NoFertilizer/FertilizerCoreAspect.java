package com.kjscott27.NoFertilizer;

import com.badlogic.gdx.utils.Array;
import fi.bugbyte.spacehaven.stuff.Production;
import fi.bugbyte.spacehaven.world.elements.Storage;
import fi.bugbyte.spacehaven.world.elements.WorldObject;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;

@Aspect
public class FertilizerCoreAspect {

    public static final int FERTILIZER_ELEMENT_ID = 2475;

    // Toggle set by clicking the [No Fert] label in the grow bed panel (FertilizerUIAspect).
    public static volatile boolean fertilizerFreeMode = false;

    // Beds whose current growth stage started without fertilizer; they grow at 50% rate.
    // WeakHashMap: entries are GC'd automatically when a bed is deconstructed.
    // Synchronized: GrowHub.update() may run on a game thread separate from the UI thread.
    static final Set<WorldObject.GrowPlace> noFertBeds =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<WorldObject.GrowPlace, Boolean>()));

    // Reflection handles for GrowPlace.current and GrowStage.needs, cached after first use.
    // Looked up on WorldObject.GrowPlace.class directly to survive any AspectJ LTW subclassing.
    private static volatile Field cachedCurrentField;
    private static volatile Field cachedNeedsField;
    // Reflection handle for GrowPlace.canGrow boolean — used to force re-evaluation when mode turns OFF.
    private static volatile Field cachedCanGrowField;

    // Intercepts GrowPlace.consumeNeeds() — called by GrowHub.update() when canGrow is false.
    // Decides per-bed whether fertilizer is required:
    //   Toggle OFF / fert present  -> vanilla behaviour; ensure bed is removed from noFertBeds.
    //   Toggle ON, fert absent    -> temporarily strip the fertilizer Need so the bed
    //                                passes the water-only check and sets canGrow = true;
    //                                add to noFertBeds for the 50% rate penalty; restore Need.
    @Pointcut("execution(void fi.bugbyte.spacehaven.world.elements.WorldObject$GrowPlace.consumeNeeds(fi.bugbyte.spacehaven.world.elements.Storage$Inventory)) && args(inv)")
    public void consumeNeeds(Storage.Inventory inv) {}

    @Around("consumeNeeds(inv)")
    public Object aroundConsumeNeeds(ProceedingJoinPoint pjp, Storage.Inventory inv) throws Throwable {
        WorldObject.GrowPlace growPlace = (WorldObject.GrowPlace) pjp.getThis();

        if (!fertilizerFreeMode) {
            noFertBeds.remove(growPlace);
            return pjp.proceed();
        }

        // Resolve and cache Field handles on first invocation.
        // Always look up on WorldObject.GrowPlace.class so the Field is valid regardless
        // of any AspectJ subclass that might be created during load-time weaving.
        Field currentField = cachedCurrentField;
        Field needsField = cachedNeedsField;
        Production.Need fertNeed = null;

        try {
            if (currentField == null) {
                currentField = WorldObject.GrowPlace.class.getDeclaredField("current");
                currentField.setAccessible(true);
                cachedCurrentField = currentField;
            }
            Object currentStage = currentField.get(growPlace);

            if (currentStage != null) {
                if (needsField == null) {
                    needsField = currentStage.getClass().getDeclaredField("needs");
                    needsField.setAccessible(true);
                    cachedNeedsField = needsField;
                }

                @SuppressWarnings("unchecked")
                Array<Production.Need> needs = (Array<Production.Need>) needsField.get(currentStage);

                if (needs != null) {
                    for (int i = 0; i < needs.size; i++) {
                        Production.Need n = needs.get(i);
                        if (n != null && n.element == FERTILIZER_ELEMENT_ID) {
                            fertNeed = n;
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Reflection failed — fall back to vanilla (bed not tracked)
            noFertBeds.remove(growPlace);
            return pjp.proceed();
        }

        // No fertilizer Need in this stage — proceed normally
        if (fertNeed == null) {
            noFertBeds.remove(growPlace);
            return pjp.proceed();
        }

        // Use the game's own canConsume logic to determine if fertilizer is available
        if (inv.canConsume(fertNeed)) {
            // Fertilizer available — consume normally, full rate
            noFertBeds.remove(growPlace);
            return pjp.proceed();
        }

        // Fertilizer absent — mark bed for 50% penalty, strip Need so remaining needs are checked
        noFertBeds.add(growPlace);

        boolean removed = false;
        try {
            Object currentStage = currentField.get(growPlace);
            if (currentStage != null && needsField != null) {
                @SuppressWarnings("unchecked")
                Array<Production.Need> needs = (Array<Production.Need>) needsField.get(currentStage);
                if (needs != null) {
                    for (int i = 0; i < needs.size; i++) {
                        Production.Need n = needs.get(i);
                        if (n != null && n.element == FERTILIZER_ELEMENT_ID) {
                            needs.removeIndex(i);
                            removed = true;
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            noFertBeds.remove(growPlace);
            return pjp.proceed();
        }

        try {
            return pjp.proceed();
        } finally {
            // Restore the Need so the crop stage definition is not permanently altered
            if (removed) {
                try {
                    Object currentStage = currentField.get(growPlace);
                    if (currentStage != null && needsField != null) {
                        @SuppressWarnings("unchecked")
                        Array<Production.Need> needs = (Array<Production.Need>) needsField.get(currentStage);
                        if (needs != null) {
                            needs.add(fertNeed);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
    }


    // getAllNeeds() is called by JobManager.checkMissingRes() to set the per-facility
    // NoRes icon and the ship-level "INSUFFICIENT RESOURCES" banner. Strip fertilizer
    // from the result when the toggle is ON so neither indicator fires for missing fertilizer.
    // Note: getAllNeeds() rebuilds its internal array each call (allNeeds.clear()), so
    // mutating the returned list is safe. Crew hauling is unaffected — it uses getNextNeeds().
    @Pointcut("execution(com.badlogic.gdx.utils.Array fi.bugbyte.spacehaven.world.elements.WorldObject$GrowHub.getAllNeeds())")
    public void growHubGetAllNeeds() {}

    @Around("growHubGetAllNeeds()")
    public Object aroundGrowHubGetAllNeeds(ProceedingJoinPoint pjp) throws Throwable {
        @SuppressWarnings("unchecked")
        Array<Production.Need> result = (Array<Production.Need>) pjp.proceed();
        if (fertilizerFreeMode && result != null) {
            for (int i = result.size - 1; i >= 0; i--) {
                if (result.get(i).element == FERTILIZER_ELEMENT_ID) {
                    result.removeIndex(i);
                }
            }
        }
        return result;
    }


    // Forces GrowPlace.canGrow back to false so GrowHub.update() calls consumeNeeds() again on
    // the next tick, re-evaluating whether fertilizer is present after the mode is turned off.
    private static void resetCanGrow(WorldObject.GrowPlace growPlace) {
        try {
            Field f = cachedCanGrowField;
            if (f == null) {
                f = WorldObject.GrowPlace.class.getDeclaredField("canGrow");
                f.setAccessible(true);
                cachedCanGrowField = f;
            }
            f.set(growPlace, false);
        } catch (Exception e) {
            System.err.println("[NoFertilizer] resetCanGrow failed: " + e);
        }
    }


    // getUpgradeValue() is the last factor applied to the growth rate inside GrowPlace.update().
    // Halving it here halves the final effective rate while preserving all other factors
    // (light, CO2, skill, research). Only applied to beds in noFertBeds — those whose current
    // stage started without fertilizer (recorded by consumeNeeds interception above).
    @Pointcut("call(float fi.bugbyte.spacehaven.world.World.getUpgradeValue(fi.bugbyte.spacehaven.stuff.ResearchUnlocks$UpgradeType, fi.bugbyte.spacehaven.stuff.FactionUtils$FactionSide)) && withincode(void fi.bugbyte.spacehaven.world.elements.WorldObject$GrowPlace.update(float)) && this(growPlace)")
    public void growPlaceUpgradeValue(WorldObject.GrowPlace growPlace) {}

    @Around("growPlaceUpgradeValue(growPlace)")
    public Object aroundGrowPlaceUpgradeValue(ProceedingJoinPoint pjp, WorldObject.GrowPlace growPlace) throws Throwable {
        float result = (Float) pjp.proceed();
        if (fertilizerFreeMode && noFertBeds.contains(growPlace)) {
            return result * 0.5f;
        }
        // Mode was turned OFF while this bed was growing without fertilizer.
        // Reset canGrow so GrowHub.update() calls consumeNeeds() again on the next tick,
        // which will re-evaluate whether fertilizer is present and stall the plant if it isn't.
        if (!fertilizerFreeMode && noFertBeds.remove(growPlace)) {
            resetCanGrow(growPlace);
        }
        return result;
    }
}
