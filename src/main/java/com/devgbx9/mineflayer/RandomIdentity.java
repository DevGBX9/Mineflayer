package com.devgbx9.mineflayer;

import java.security.SecureRandom;

/**
 * Generates throwaway player names.
 *
 * <p>Shared by the local fake player and the remote bot so that "a new identity
 * every join" means the same thing in both, rather than two generators drifting
 * apart in length or alphabet.
 */
public final class RandomIdentity {

    private RandomIdentity() {
    }

    /**
     * Source for generated names.
     *
     * <p>{@link SecureRandom} rather than {@code Random}: two servers starting
     * from the same seed would generate the same sequence of names, and a name
     * meant to be unrecognisable should not be predictable from elsewhere.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What Minecraft accepts in a name: letters, digits and underscore. */
    private static final String ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_";

    /**
     * Length of a generated name.
     *
     * <p>Twelve of sixteen allowed characters. Long enough that colliding with a
     * real player's name is not a practical concern, short enough to stay
     * readable in a player list and in the console.
     */
    private static final int LENGTH = 12;

    /** A name seen nowhere before, valid for both offline login and a profile. */
    public static String name() {
        StringBuilder out = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }
}
