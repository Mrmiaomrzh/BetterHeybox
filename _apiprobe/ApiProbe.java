import com.better.heybox.hooks.GameLibraryCleanHook;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ApiProbe {
    public static void probe() {
        Set<String> types = GameLibraryCleanHook.selectedTypes();
        Set<String> entries = GameLibraryCleanHook.selectedNames(GameLibraryCleanHook.PICK_ENTRY);
        Set<String> sections = GameLibraryCleanHook.selectedNames(GameLibraryCleanHook.PICK_SECTION);
        int t = GameLibraryCleanHook.PICK_TYPE;
        String diag = GameLibraryCleanHook.diagnostics();
        List<String[]> p1 = GameLibraryCleanHook.pickerEntries(t);
        List<String[]> p2 = GameLibraryCleanHook.pickerEntries();
        GameLibraryCleanHook.refresh();
        GameLibraryCleanHook.setSelectedTypes(types);
        GameLibraryCleanHook.setSelectedNames(t, sections);
        String hint = GameLibraryCleanHook.coverageHint("header");
        GameLibraryCleanHook.setHostVersionCode(42L);

        String[] anchors = GameLibraryCleanHook.CLASS_ANCHORS;
        String[] bbAnchors = GameLibraryCleanHook.BB_CLASS_ANCHORS;
        String[][] catalog = GameLibraryCleanHook.TYPE_CATALOG;
        String k1 = GameLibraryCleanHook.TARGET_GAME_REC_BIND;
        String k2 = GameLibraryCleanHook.TARGET_GAME_REC_WRAPPER;
        String k3 = GameLibraryCleanHook.TARGET_GAME_REC_BB;
        String c1 = GameLibraryCleanHook.ADAPTER_CLASS;
        String c2 = GameLibraryCleanHook.WRAPPER_CLASS;
        String c3 = GameLibraryCleanHook.BB_DELEGATE_CLASS;

        java.util.function.Consumer<ClassLoader> installer =
                new GameLibraryCleanHook(null)::install;
        List<String[]> all = new ArrayList<>();
        all.addAll(p1);
        all.addAll(p2);
        if (types == null || entries == null || sections == null || diag == null || hint == null
                || anchors == null || bbAnchors == null || catalog == null
                || k1 == null || k2 == null || k3 == null || c1 == null || c2 == null || c3 == null
                || installer == null || all.isEmpty()) {
            throw new IllegalStateException("unreachable");
        }
    }
}
