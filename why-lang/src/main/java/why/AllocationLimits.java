package why;

/** Hard per-request allocation caps, in UTF-8 bytes unless noted. */
public final class AllocationLimits {
    /** Maximum rows one database query may return. */
    public static final int DB_ROWS = 10_000;

    /** Maximum total bytes of column labels and values one database query may return. */
    public static final long DB_BYTES = 4L * 1024 * 1024;

    /** Maximum size of one static file served from the site. */
    public static final long STATIC_ASSET_BYTES = 4L * 1024 * 1024;

    /** Maximum UTF-8 size of one flash value. */
    public static final int FLASH_VALUE_BYTES = 4 * 1024;

    /** Maximum size of a page file, including imported pages. */
    public static final long PAGE_BYTES = 256 * 1024;

    private AllocationLimits() {}
}
