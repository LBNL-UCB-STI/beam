package beam.utils.mapsapi.hereapi;

/*
 * Copyright (C) 2019 HERE Europe B.V.
 * Licensed under MIT, see full license in LICENSE
 * SPDX-License-Identifier: MIT
 * License-Filename: LICENSE
 */

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The polyline encoding is a lossy compressed representation of a list of coordinate pairs or coordinate triples.
 * It achieves that by:
 * <p><ol>
 * <li>Reducing the decimal digits of each value.
 * <li>Encoding only the offset from the previous point.
 * <li>Using variable length for each coordinate delta.
 * <li>Using 64 URL-safe characters to display the result.
 * </ol><p>
 *
 * The advantage of this encoding are the following:
 * <p><ul>
 * <li> Output string is composed by only URL-safe characters
 * <li> Floating point precision is configurable
 * <li> It allows to encode a 3rd dimension with a given precision, which may be a level, altitude, elevation or some other custom value
 * </ul><p>
 */
class PolylineEncoderDecoder {

    /**
     * Header version
     * A change in the version may affect the logic to encode and decode the rest of the header and data
     */
    public static final byte FORMAT_VERSION = 1;

    //Base64 URL-safe characters
    public static final char[] ENCODING_TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    public static final  int[] DECODING_TABLE = {
            62, -1, -1, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1, -1,
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21,
            22, 23, 24, 25, -1, -1, -1, -1, 63, -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51
    };
    /**
     * Encode the list of coordinate triples.<BR><BR>
     * The third dimension value will be eligible for encoding only when ThirdDimension is other than ABSENT.
     * This is lossy compression based on precision accuracy.
     *
     * @param coordinates {@link List} of coordinate triples that to be encoded.
     * @param precision   Floating point precision of the coordinate to be encoded.
     * @param thirdDimension {@link ThirdDimension} which may be a level, altitude, elevation or some other custom value
     * @param thirdDimPrecision Floating point precision for thirdDimension value
     * @return URL-safe encoded {@link String} for the given coordinates.
     */
    public static String encode(List<LatLngZ> coordinates, int precision, ThirdDimension thirdDimension, int thirdDimPrecision) {
        if (coordinates == null || coordinates.size() == 0) {
            throw new IllegalArgumentException("Invalid coordinates!");
        }
        if (thirdDimension == null) {
            throw new IllegalArgumentException("Invalid thirdDimension");
        }
        Encoder enc = new Encoder(precision, thirdDimension, thirdDimPrecision);
        Iterator<LatLngZ> iter = coordinates.iterator();
        while (iter.hasNext()) {
            enc.add(iter.next());
        }
        return enc.getEncoded();
    }

    /**
     * Decode the encoded input {@link String} to {@link List} of coordinate triples.<BR><BR>
     * @param encoded URL-safe encoded {@link String}
     * @return {@link List} of coordinate triples that are decoded from input
     *
     * @see PolylineEncoderDecoder#getThirdDimension(String) getThirdDimension
     * @see LatLngZ
     */
    public static List<LatLngZ> decode(String encoded) {

        if (encoded == null || encoded.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid argument!");
        }
        List<LatLngZ> result = new ArrayList<>();
        Decoder dec = new Decoder(encoded);
        AtomicReference<Double> lat = new AtomicReference<>(0d);
        AtomicReference<Double> lng = new AtomicReference<>(0d);
        AtomicReference<Double> z   = new AtomicReference<>(0d);

        while (dec.decodeOne(lat, lng, z)) {
            result.add(new LatLngZ(lat.get(), lng.get(), z.get()));
            lat = new AtomicReference<>(0d);
            lng = new AtomicReference<>(0d);
            z   = new AtomicReference<>(0d);
        }
        return result;
    }

    /**
     * ThirdDimension type from the encoded input {@link String}
     * @param encoded URL-safe encoded coordinate triples {@link String}
     * @return type of {@link ThirdDimension}
     */
    public static ThirdDimension getThirdDimension(String encoded) {
        AtomicInteger index = new AtomicInteger(0);
        AtomicLong header = new AtomicLong(0);
        Decoder.decodeHeaderFromString(encoded, index, header);
        return ThirdDimension.fromNum((header.get() >> 4) & 7);
    }

    public byte getVersion() {
        return FORMAT_VERSION;
    }

    /*
     * Single instance for configuration, validation and encoding for an input request.
     */
    private static class Encoder {

        private final StringBuilder result;
        private final Converter latConveter;
        private final Converter lngConveter;
        private final Converter zConveter;
        private final ThirdDimension thirdDimension;

        public Encoder(int precision, ThirdDimension thirdDimension, int thirdDimPrecision) {
            this.latConveter = new Converter(precision);
            this.lngConveter = new Converter(precision);
            this.zConveter = new Converter(thirdDimPrecision);
            this.thirdDimension = thirdDimension;
            this.result = new StringBuilder();
            encodeHeader(precision, this.thirdDimension.getNum(), thirdDimPrecision);
        }

        private void encodeHeader(int precision, int thirdDimensionValue, int thirdDimPrecision) {
            /*
             * Encode the `precision`, `third_dim` and `third_dim_precision` into one encoded char
             */
            if (precision < 0 || precision > 15) {
                throw new IllegalArgumentException("precision out of range");
            }

            if (thirdDimPrecision < 0 || thirdDimPrecision > 15) {
                throw new IllegalArgumentException("thirdDimPrecision out of range");
            }

            if (thirdDimensionValue < 0 || thirdDimensionValue > 7) {
                throw new IllegalArgumentException("thirdDimensionValue out of range");
            }
            long res = (thirdDimPrecision << 7) | (thirdDimensionValue << 4) | precision;
            Converter.encodeUnsignedVarint(PolylineEncoderDecoder.FORMAT_VERSION, result);
            Converter.encodeUnsignedVarint(res, result);
        }

        private void add(double lat, double lng) {
            latConveter.encodeValue(lat, result);
            lngConveter.encodeValue(lng, result);
        }

        private void add(double lat, double lng, double z) {
            add(lat, lng);
            if (this.thirdDimension != ThirdDimension.ABSENT) {
                zConveter.encodeValue(z, result);
            }
        }

        private void add(LatLngZ tuple) {
            if(tuple == null) {
                throw new IllegalArgumentException("Invalid LatLngZ tuple");
            }
            add(tuple.lat, tuple.lng, tuple.z);
        }

        private String getEncoded() {
            return this.result.toString();
        }
    }

    /*
     * Single instance for decoding an input request.
     */
    private static class Decoder {

        private final String encoded;
        private final AtomicInteger index;
        private final Converter latConveter;
        private final Converter lngConveter;
        private final Converter zConveter;

        private int precision;
        private int thirdDimPrecision;
        private ThirdDimension thirdDimension;


        public Decoder(String encoded) {
            this.encoded = encoded;
            this.index = new AtomicInteger(0);
            decodeHeader();
            this.latConveter = new Converter(precision);
            this.lngConveter = new Converter(precision);
            this.zConveter = new Converter(thirdDimPrecision);
        }

        private boolean hasThirdDimension() {
            return thirdDimension != ThirdDimension.ABSENT;
        }

        private void decodeHeader() {
            AtomicLong header = new AtomicLong(0);
            decodeHeaderFromString(encoded, index, header);
            precision = (int) (header.get() & 15); // we pick the first 4 bits only
            header.set(header.get() >> 4);
            thirdDimension = ThirdDimension.fromNum(header.get() & 7); // we pick the first 3 bits only
            thirdDimPrecision = (int) ((header.get() >> 3) & 15);
        }

        private static void decodeHeaderFromString(String encoded, AtomicInteger index, AtomicLong header) {
            AtomicLong value = new AtomicLong(0);

            // Decode the header version
            if(!Converter.decodeUnsignedVarint(encoded.toCharArray(), index, value)) {
                throw new IllegalArgumentException("Invalid encoding");
            }
            if (value.get() != FORMAT_VERSION) {
                throw new IllegalArgumentException("Invalid format version");
            }
            // Decode the polyline header
            if(!Converter.decodeUnsignedVarint(encoded.toCharArray(), index, value)) {
                throw new IllegalArgumentException("Invalid encoding");
            }
            header.set(value.get());
        }


        private boolean decodeOne(AtomicReference<Double> lat,
                AtomicReference<Double> lng,
                AtomicReference<Double> z) {
            if (index.get() == encoded.length()) {
                return false;
            }
            if (!latConveter.decodeValue(encoded, index, lat)) {
                throw new IllegalArgumentException("Invalid encoding");
            }
            if (!lngConveter.decodeValue(encoded, index, lng)) {
                throw new IllegalArgumentException("Invalid encoding");
            }
            if (hasThirdDimension()) {
                if (!zConveter.decodeValue(encoded, index, z)) {
                    throw new IllegalArgumentException("Invalid encoding");
                }
            }
            return true;
        }
    }

    //Decode a single char to the corresponding value
    private static int decodeChar(char charValue) {
        int pos = charValue - 45;
        if (pos < 0 || pos > 77) {
            return -1;
        }
        return DECODING_TABLE[pos];
    }

    /*
     * Stateful instance for encoding and decoding on a sequence of Coordinates part of an request.
     * Instance should be specific to type of coordinates (e.g. Lat, Lng)
     * so that specific type delta is computed for encoding.
     * Lat0 Lng0 3rd0 (Lat1-Lat0) (Lng1-Lng0) (3rdDim1-3rdDim0)
     */
    public static class Converter {

        private long multiplier = 0;
        private long lastValue = 0;

        public Converter(int precision) {
            setPrecision(precision);
        }

        private void setPrecision(int precision) {
            multiplier = (long) Math.pow(10, precision);
        }

        private static void encodeUnsignedVarint(long value, StringBuilder result) {
            while (value > 0x1F) {
                byte pos =  (byte) ((value & 0x1F) | 0x20);
                result.append(ENCODING_TABLE[pos]);
                value >>= 5;
            }
            result.append(ENCODING_TABLE[(byte) value]);
        }

        void encodeValue(double value, StringBuilder result) {
            /*
             * Round-half-up
             * round(-1.4) --> -1
             * round(-1.5) --> -2
             * round(-2.5) --> -3
             */
            long scaledValue = Math.round(Math.abs(value * multiplier)) * Math.round(Math.signum(value));
            long delta = scaledValue - lastValue;
            boolean negative = delta < 0;

            lastValue = scaledValue;

            // make room on lowest bit
            delta <<= 1;

            // invert bits if the value is negative
            if (negative) {
                delta = ~delta;
            }
            encodeUnsignedVarint(delta, result);
        }

        private static boolean decodeUnsignedVarint(char[] encoded,
                AtomicInteger index,
                AtomicLong result) {
            short shift = 0;
            long delta = 0;
            long value;

            while (index.get() < encoded.length) {
                value = decodeChar(encoded[index.get()]);
                if (value < 0) {
                    return false;
                }
                index.incrementAndGet();
                delta |= (value & 0x1F) << shift;
                if ((value & 0x20) == 0) {
                    result.set(delta);
                    return true;
                } else {
                    shift += 5;
                }
            }

            return shift <= 0;
        }

        //Decode single coordinate (say lat|lng|z) starting at index
        boolean decodeValue(String encoded,
                AtomicInteger index,
                AtomicReference<Double> coordinate) {
            AtomicLong delta = new AtomicLong();
            if (!decodeUnsignedVarint(encoded.toCharArray(), index, delta)) {
                return false;
            }
            if ((delta.get() & 1) != 0) {
                delta.set(~delta.get());
            }
            delta.set(delta.get()>>1);
            lastValue += delta.get();
            coordinate.set(((double)lastValue / multiplier));
            return true;
        }
    }

    /**
     * 	3rd dimension specification.
     *  Example a level, altitude, elevation or some other custom value.
     *  ABSENT is default when there is no third dimension en/decoding required.
     */
    public enum ThirdDimension {
        ABSENT(0),
        LEVEL(1),
        ALTITUDE(2),
        ELEVATION(3),
        RESERVED1(4),
        RESERVED2(5),
        CUSTOM1(6),
        CUSTOM2(7);

        private final int num;

        ThirdDimension(int num) {
            this.num = num;
        }

        public int getNum() {
            return num;
        }

        public static ThirdDimension fromNum(long value) {
            for (ThirdDimension dim : ThirdDimension.values()) {
                if (dim.getNum() == value) {
                    return dim;
                }
            }
            return null;
        }
    }

    /**
     * Coordinate triple
     */
    public static class LatLngZ {
        public final double lat;
        public final double lng;
        public final double z;

        public LatLngZ (double latitude, double longitude) {
            this(latitude, longitude, 0);
        }

        public LatLngZ (double latitude, double longitude, double thirdDimension) {
            this.lat = latitude;
            this.lng = longitude;
            this.z   = thirdDimension;
        }

        @Override
        public String toString() {
            return "LatLngZ [lat=" + lat + ", lng=" + lng + ", z=" + z + "]";
        }

        @Override
        public boolean equals(Object anObject) {
            if (this == anObject) {
                return true;
            }
            if (anObject instanceof LatLngZ) {
                LatLngZ passed = (LatLngZ)anObject;
                if (passed.lat == this.lat && passed.lng == this.lng && passed.z == this.z) {
                    return true;
                }
            }
            return false;
        }
    }
}
