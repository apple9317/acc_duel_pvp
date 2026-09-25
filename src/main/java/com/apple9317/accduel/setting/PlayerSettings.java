package com.apple9317.accduel.setting;

/**
 * 玩家个人设置（data/settings.json，Gson 存储）。
 */
public class PlayerSettings {

    /** 是否接受他人的决斗申请。 */
    public boolean acceptRequests = true;

    /**
     * 个人击杀特效 id（对应 {@link com.apple9317.accduel.killeffect.KillEffect.Type}）；
     * "default" 表示沿用服务器全局配置 match.kill-effect。
     */
    public String killEffect = "none";

    /** 是否使用新版本屏幕 UI（Dialog，仅对 1.21.6+ 客户端有效）；关闭则用箱子界面。 */
    public boolean modernUi = true;
}
