package cgeo.geocaching.connector.tc;

import cgeo.geocaching.Geocache;
import cgeo.geocaching.connector.AbstractConnector;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import java.util.regex.Pattern;

public class TerraCachingConnector extends AbstractConnector {

    @NonNull private final static Pattern PATTERN_GEOCODE = Pattern.compile("TC[0-9A-Z]{1,3}|CC[0-9A-Z]{4}", Pattern.CASE_INSENSITIVE);

    @Override
    @NonNull
    public String getName() {
        return "TerraCaching";
    }

    @Override
    @Nullable
    public String getCacheUrl(@NonNull final Geocache cache) {
        return getCacheUrlPrefix() + cache.getGeocode();
    }

    @Override
    @NonNull
    public String getHost() {
        return "www.terracaching.com/";
    }

    @Override
    public boolean isOwner(@NonNull final Geocache cache) {
        return false;
    }

    @Override
    @NonNull
    protected String getCacheUrlPrefix() {
        return "http://www.terracaching.com/Cache/";
    }

    @Override
    public boolean canHandle(@NonNull final String geocode) {
        return PATTERN_GEOCODE.matcher(geocode).matches();
    }
}
