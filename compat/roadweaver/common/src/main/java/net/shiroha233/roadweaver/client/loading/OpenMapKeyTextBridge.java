package net.shiroha233.roadweaver.client.loading;

import dev.architectury.injectables.annotations.ExpectPlatform;

/**
 * 加载提示中“打开地图”按键文案的跨平台桥接。
 */
public final class OpenMapKeyTextBridge {
    private OpenMapKeyTextBridge() {
    }

    @ExpectPlatform
    public static String getDisplayText() {
        throw new AssertionError();
    }
}