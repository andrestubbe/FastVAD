package fastvad.ansi;

public final class FastVADAnsi {
    public static final String RESET  = "\u001B[0m";
    public static final String BOLD   = "\u001B[1m";
    public static final String CYAN   = "\u001B[36m";
    public static final String GREEN  = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String RED    = "\u001B[31m";
    public static final String GRAY   = "\u001B[90m";
    public static final String WHITE  = "\u001B[97m";

    public static void printHeader(String title, String subtitle) {
        System.out.println(CYAN + "========================================================================================================================" + RESET);
        System.out.println(BOLD + WHITE + "  " + title + RESET);
        System.out.println(GRAY + "  " + subtitle + RESET);
        System.out.println(CYAN + "========================================================================================================================" + RESET);
    }

    public static void printSection(String title) {
        System.out.println("\n" + BOLD + CYAN + "── " + title + " " + "─".repeat(Math.max(2, 116 - title.length())) + RESET);
    }

    public static void printTreeItem(String key, String val, boolean isLast) {
        String branch = isLast ? "└── " : "├── ";
        System.out.println(GRAY + branch + BOLD + WHITE + key + ": " + RESET + CYAN + val + RESET);
    }
}