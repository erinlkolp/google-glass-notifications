package dev.erinlkolp.glassnotify.phone;

import dev.erinlkolp.glassnotify.wire.Tier;

/** One allowlist entry: this package, shown at this tier. */
public final class AllowRule {

    public final String packageName;
    public final Tier tier;

    public AllowRule(String packageName, Tier tier) {
        if (packageName == null) {
            throw new NullPointerException("packageName");
        }
        if (tier == null) {
            throw new NullPointerException("tier");
        }
        this.packageName = packageName;
        this.tier = tier;
    }
}
