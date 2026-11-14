/*
 * SkyLands (SkyRealms) - floating-island survival for Paper
 * Copyright (C) 2026 Hammond Digital Studios
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License version 3, together with the additional terms in the NOTICE
 * file (section 7: you must keep the attribution "SkyLands by Hammond Digital Studios" and mark
 * modified versions). This program is distributed WITHOUT ANY WARRANTY. See the LICENSE and
 * NOTICE files, or <https://www.gnu.org/licenses/>.
 */
package dev.skyrealms.bedrock;

import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.journal.Journal;
import dev.skyrealms.journal.JournalPages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The journal as Bedrock forms. Only loaded when Floodgate is installed (see BedrockUi). */
public final class JournalForms {
    private static final String P = "§d", GR = "§7", DG = "§8", W = "§f", R = "§c", G = "§a", A = "§b";

    private final SkyRealmsPlugin plugin;

    public JournalForms(SkyRealmsPlugin plugin) { this.plugin = plugin; }

    private record Btn(String text, Consumer<Player> action) {}

    private void send(Player p, org.geysermc.cumulus.form.util.FormBuilder<?, ?, ?> builder) {
        org.geysermc.cumulus.form.Form form = builder.build();
        var hook = FormMenus.testSender;
        if (hook != null) hook.accept(p, form);
        else FloodgateApi.getInstance().sendForm(p.getUniqueId(), form);
    }

    private void sync(Player p, Runnable r) {
        if (Bukkit.isPrimaryThread()) { if (p.isOnline()) r.run(); return; }
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) r.run(); });
    }

    private void simple(Player p, String title, String content, List<Btn> buttons) {
        SimpleForm.Builder f = SimpleForm.builder().title(title).content(content == null ? "" : content);
        for (Btn b : buttons) f.button(b.text);
        f.validResultHandler(r -> sync(p, () -> {
            int id = r.clickedButtonId();
            if (id >= 0 && id < buttons.size() && buttons.get(id).action != null) buttons.get(id).action.accept(p);
        }));
        send(p, f);
    }

    /** Contents, or the story first when {@code storyFirst}. */
    public void open(Player p, boolean storyFirst) {
        if (storyFirst) page(p, JournalPages.STORY);
        else contents(p);
    }

    public void contents(Player p) {
        Journal j = plugin.journal();
        List<Btn> b = new ArrayList<>();
        List<String> order = new ArrayList<>();
        order.add(JournalPages.STORY);
        order.addAll(j.countedPages());
        for (String id : order) {
            if (j.unlocked(p, id)) b.add(new Btn(A + JournalPages.get(id).title(), pl -> page(pl, id)));
            else b.add(new Btn(DG + "???", this::contents));
        }
        if (!j.finished(p)) b.add(new Btn(R + "Skip tutorial", this::confirmSkip));
        else b.add(new Btn(GR + "Restart tutorial", pl -> { j.restart(pl); contents(pl); }));
        b.add(new Btn(GR + "Close", null));
        String content = W + "Journal " + j.unlockedCount(p) + "/" + j.countedPages().size() + "\n\n" + P + "Your objective:\n" + W + j.currentObjective(p);
        simple(p, "The Guide", content, b);
    }

    private void page(Player p, String id) {
        JournalPages.Page page = JournalPages.get(id);
        simple(p, page.title(), W + page.text(), List.of(new Btn(GR + "Contents", this::contents)));
    }

    private void confirmSkip(Player p) {
        ModalForm.Builder f = ModalForm.builder().title("Skip the tutorial?")
                .content(W + "Every journal page opens now, but step rewards aren't paid. You can restart it later.")
                .button1(R + "Skip it").button2(G + "Keep going")
                .validResultHandler(r -> sync(p, () -> {
                    if (r.clickedFirst()) plugin.journal().skip(p);
                    contents(p);
                }));
        send(p, f);
    }
}
