package pl.matsuo.navi;

/**
 * The order the app list puts things in.
 *
 * <p>Alphabetical is the wrong order for a car. The apps reached while driving are always the same
 * handful, and hunting for Maps somewhere under M with a glance off the road is exactly what the
 * launcher exists to avoid. These come first, in this order, whatever their names begin with;
 * everything else follows alphabetically.
 *
 * <p>A unit that has none of them loses nothing: an entry that is not installed simply never
 * matches. Some apps ship under more than one package name depending on the ROM, so the
 * alternatives sit next to each other and the first one present wins its place.
 *
 * <p>Plain Java with no Android in it, so the order is unit tested.
 */
final class AppOrder {

    /** Package names that come before the alphabet, most wanted first. */
    static final String[] FIRST = {
        "com.android.settings",
        "com.google.android.gm",
        "com.google.android.apps.maps",
        "com.google.android.apps.nbu.files",
        "com.android.documentsui",
        "com.android.chrome",
        "com.android.vending",
        "com.google.android.youtube",
    };

    private AppOrder() {}

    /**
     * Where a package sits in the order.
     *
     * @return its place in {@link #FIRST}, or {@link #FIRST}{@code .length} for everything else,
     *     which the caller then sorts by name
     */
    static int rank(String packageName) {
        for (int i = 0; i < FIRST.length; i++) {
            if (FIRST[i].equals(packageName)) {
                return i;
            }
        }
        return FIRST.length;
    }
}
