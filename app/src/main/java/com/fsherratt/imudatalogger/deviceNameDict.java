package com.fsherratt.imudatalogger;

import java.util.HashMap;

public class deviceNameDict {
    private static HashMap<String, String> attributes = new HashMap();

    static {
        // Device Set 1
        attributes.put("0C:8C:DC:2E:32:67", "Ankle Left #1");
        attributes.put("0C:8C:DC:2E:30:DC", "Ankle Right #1");
        attributes.put("0C:8C:DC:2E:40:7D", "Hip Left #1");
        attributes.put("0C:8C:DC:2E:33:78", "Hip Right #1");
        attributes.put("0C:8C:DC:2E:3B:57", "Chest #1");

        // Device Set 2
        attributes.put("0C:8C:DC:2E:40:6C", "Ankle Left #2");
        attributes.put("0C:8C:DC:2E:3B:81", "Ankle Right #2");
        attributes.put("0C:8C:DC:2E:3B:FF", "Hip Left #2");
        attributes.put("0C:8C:DC:2E:33:3D", "Hip Right #2");
        attributes.put("0C:8C:DC:2E:34:70", "Chest #2");
    }

    public static String lookup(String phy_address) {
        return attributes.get(phy_address);
    }
}
