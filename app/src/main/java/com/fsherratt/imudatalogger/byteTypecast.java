package com.fsherratt.imudatalogger;

public class byteTypecast {
    public static String bytesToHex(byte[] hashInBytes) {

        StringBuilder sb = new StringBuilder();
        for (byte b : hashInBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static String bytesToHex(byte[] hashInBytes, int offset) {

        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < hashInBytes.length; i++) {
//        for (byte b : hashInBytes) {
            sb.append(String.format("%02x", hashInBytes[i]));
        }
        return sb.toString();
    }

    public static int bytesToInt16( byte[] data, int offset ) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    public static int bytesToInt32( byte[] data, int offset ) {
        return ( data[offset] & 0xFF)
                | ((data[offset+1] & 0xFF) << 8)
                | ((data[offset +2] & 0xFF) << 16)
                | ((data[offset +3] & 0xFF) << 24);
    }

    public static float bytesToFloat( byte[] data, int offset ) {
        int intBits = bytesToInt32( data, offset);
        return Float.intBitsToFloat(intBits);
    }

    public static byte[] int32ToBytes( int val ) {
        return new byte[]{ (byte)(val & 0xFF),
                (byte)((val >> 8)  & 0xFF),
                (byte)((val >> 16) & 0xFF),
                (byte)((val >> 24) & 0xFF) };
    }

    public static byte[] floatToBytes( float val ) {
        int intBits = Float.floatToIntBits( val );
        return int32ToBytes(intBits);
    }
}
