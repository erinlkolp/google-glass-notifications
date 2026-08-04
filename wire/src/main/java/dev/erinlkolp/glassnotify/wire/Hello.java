package dev.erinlkolp.glassnotify.wire;

/** Handshake sent by the phone immediately after connecting. */
public final class Hello {

    public final String deviceName;
    public final String deviceAddress;

    public Hello(String deviceName, String deviceAddress) {
        if (deviceName == null) {
            throw new NullPointerException("deviceName");
        }
        if (deviceAddress == null) {
            throw new NullPointerException("deviceAddress");
        }
        this.deviceName = deviceName;
        this.deviceAddress = deviceAddress;
    }

    @Override
    public String toString() {
        return "Hello{" + deviceName + " " + deviceAddress + "}";
    }
}
