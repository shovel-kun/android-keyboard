package org.futo.inputmethod.latin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AudioAndHapticFeedbackManagerTest {
    @Test
    public void isValidVibrationDurationRejectsZeroAndNegativeDurations() {
        assertFalse(AudioAndHapticFeedbackManager.isValidVibrationDuration(0));
        assertFalse(AudioAndHapticFeedbackManager.isValidVibrationDuration(-1));
    }

    @Test
    public void isValidVibrationDurationAcceptsPositiveDurations() {
        assertTrue(AudioAndHapticFeedbackManager.isValidVibrationDuration(1));
        assertTrue(AudioAndHapticFeedbackManager.isValidVibrationDuration(50));
    }
}
