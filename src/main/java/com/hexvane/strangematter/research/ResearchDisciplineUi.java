package com.hexvane.strangematter.research;

import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/** One typed native texture mapping for research pages and scanner feedback. */
public final class ResearchDisciplineUi {
    private ResearchDisciplineUi() { }
    public static void icon(UICommandBuilder commands,String selector,ResearchType type) {
        commands.setObject(selector+".Background",new PatchStyle().setTexturePath(Value.of(type.uiIconPath())));
    }
}
