package local.mintv.search;

/** Search filters exposed by the original client; {@link #itemType} is the BFF parameter. */
public enum SearchType {
    ALL(""),
    MOVIES("Movie"),
    SHOWS("TVShow"),
    PEOPLE("Person"),
    STREAMING("Streaming");

    public final String itemType;

    SearchType(String itemType) {
        this.itemType = itemType;
    }
}
