package com.kjscott27.NoFertilizer;

import fi.bugbyte.framework.screen.StageButton;
import fi.bugbyte.gen.compiled.ToggleTextIconButton1;
import fi.bugbyte.gen.compiled.ToggleTextIconButtons1;
import fi.bugbyte.spacehaven.gui.MenuSystem;
import fi.bugbyte.spacehaven.gui.MenuSystemItems;
import fi.bugbyte.spacehaven.gui.WorldElementInfos;

import java.lang.reflect.Field;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;

@Aspect
public class FertilizerUIAspect {

    // Intercepts SingleWorldElementSelected.open() — the method the game calls when any
    // facility is selected and its command bar is being built (same lifecycle point used by
    // LOGISTICS, PAUSE, DISMANTLE, etc.). We inspect CollectedFacilityInfo.growPlaces via
    // reflection; if non-null the selected element is a grow bed and we inject our toggle.

    @Pointcut("execution(* fi.bugbyte.spacehaven.gui.MenuSystemItems$SingleWorldElementSelected.open(..))")
    public void singleElementOpen() {}

    @After("singleElementOpen()")
    public void afterOpen(JoinPoint jp) {
        try {
            MenuSystemItems.SingleWorldElementSelected sel =
                    (MenuSystemItems.SingleWorldElementSelected) jp.getThis();

            WorldElementInfos.CollectedFacilityInfo info = sel.getCollectedInfo();
            if (info == null) return;

            // growPlaces is package-private — reflect to check if this is a grow bed
            Field growPlacesField = info.getClass().getDeclaredField("growPlaces");
            growPlacesField.setAccessible(true);
            if (growPlacesField.get(info) == null) return;

            MenuSystem.SelectionBox box = (MenuSystem.SelectionBox) jp.getArgs()[0];

            final ToggleTextIconButton1 btn = ToggleTextIconButtons1.getFacilityNoRefill();
            btn.setText("NO FERT");
            btn.setHoldDown(FertilizerCoreAspect.fertilizerFreeMode);

            btn.setClickHandler(new StageButton.clickHandler() {
                @Override
                public void clicked() {
                    FertilizerCoreAspect.fertilizerFreeMode = !FertilizerCoreAspect.fertilizerFreeMode;
                    btn.setHoldDown(FertilizerCoreAspect.fertilizerFreeMode);
                }
            });

            box.getMenuSystem().addCommandButton((StageButton) btn);

        } catch (Exception e) {
            System.err.println("[NoFertilizer] Error adding NO FERT button: " + e);
        }
    }
}
