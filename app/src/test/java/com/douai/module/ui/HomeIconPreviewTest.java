package com.douai.module.ui;

import android.graphics.Bitmap;
import android.graphics.Color;
import com.douai.module.config.HomeUiIconFixtures;
import com.douai.module.feature.MediaMemoryBudget;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomeIconPreviewTest {
    @Test public void previewKeepsActualPixelsAndReleasesItsBitmapAndBudgetOnClose() {
        HomeIconPreview preview=HomeIconPreview.decode(HomeUiIconFixtures.webp(12,8,Color.BLUE));
        Bitmap bitmap=preview.bitmap();
        assertEquals(Color.BLUE,bitmap.getPixel(5,4));
        assertNull(MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES));
        preview.close();preview.close();assertTrue(bitmap.isRecycled());
        try(MediaMemoryBudget.Reservation all=MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES)) {
            assertNotNull(all);
        }
    }

    @Test public void aBusyBudgetRejectsPreviewWithoutKeepingAnyReservation() {
        byte[] bytes=HomeUiIconFixtures.webp(8,8,Color.RED);
        try(MediaMemoryBudget.Reservation all=MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES)) {
            assertNotNull(all);assertThrows(IllegalArgumentException.class,()->HomeIconPreview.decode(bytes));
        }
        try(MediaMemoryBudget.Reservation all=MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES)) {
            assertNotNull(all);
        }
    }

    @Test public void malformedImagesLeaveTheBudgetAvailable() {
        assertThrows(IllegalArgumentException.class,()->HomeIconPreview.decode(new byte[]{1,2,3}));
        try(MediaMemoryBudget.Reservation all=MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES)) {
            assertNotNull(all);
        }
    }
}
