package com.vortex.poker.display;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The card maths Vortex checks by eye. An item model's top edge is local +Y; the side that faces
 * up when a card lies flat (the one players read) is local -Z.
 */
class CardRotationTest {
    private static void assertVec(double x, double y, double z, Vector3f v, String what) {
        assertEquals(x, v.x, 1e-5, what + " x");
        assertEquals(y, v.y, 1e-5, what + " y");
        assertEquals(z, v.z, 1e-5, what + " z");
    }

    @Test
    void flatCardsMatchBlackjacksConvention() {
        // top edge along (-sin r, 0, cos r), readable face up, for every quarter turn
        for (int yaw = -180; yaw < 180; yaw += 90) {
            Quaternionf q = WorldTableView.cardRotation(yaw, 0);
            double r = Math.toRadians(yaw);
            assertVec(-Math.sin(r), 0, Math.cos(r), q.transform(new Vector3f(0, 1, 0)), "top at yaw " + yaw);
            assertVec(0, 1, 0, q.transform(new Vector3f(0, 0, -1)), "face at yaw " + yaw);
        }
    }

    @Test
    void tiltedCardsLiftTheirTopAndFaceTheReader() {
        double tilt = Math.toRadians(56);
        for (int yaw = -180; yaw < 180; yaw += 90) {
            Quaternionf q = WorldTableView.cardRotation(yaw, tilt);
            double r = Math.toRadians(yaw);
            double tx = -Math.sin(r), tz = Math.cos(r);
            assertVec(tx * Math.cos(tilt), Math.sin(tilt), tz * Math.cos(tilt),
                q.transform(new Vector3f(0, 1, 0)), "top at yaw " + yaw);
            // the reader sits on the -top side: the face leans back towards them
            assertVec(-tx * Math.sin(tilt), Math.cos(tilt), -tz * Math.sin(tilt),
                q.transform(new Vector3f(0, 0, -1)), "face at yaw " + yaw);
        }
    }

    @Test
    void theButtonLetterLiesFaceUpAndReadsFromTheButtonSeat() {
        // a text display's front is local +Z, its top local +Y
        for (int yaw = -180; yaw < 180; yaw += 90) {
            Quaternionf q = WorldTableView.flatTextRotation(yaw);
            double r = Math.toRadians(yaw);
            assertVec(0, 1, 0, q.transform(new Vector3f(0, 0, 1)), "front at yaw " + yaw);
            assertVec(-Math.sin(r), 0, Math.cos(r), q.transform(new Vector3f(0, 1, 0)), "top at yaw " + yaw);
            // and not mirrored: the text's right is the reader's right (top x up, seen from above)
            assertVec(-Math.cos(r), 0, -Math.sin(r), q.transform(new Vector3f(1, 0, 0)), "right at yaw " + yaw);
        }
    }

    @Test
    void sideKeysAreDistinct() {
        double[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        java.util.Set<Integer> keys = new java.util.HashSet<>();
        for (double[] d : dirs) keys.add(WorldTableView.sideKey(d));
        assertEquals(4, keys.size());
    }
}
