package com.polaris.app;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

/**
 * 动画预览（开发者工具）：列出主页全部动画，点选后跳到主页在真实场景中单播一次——
 * 天文台入场 / 火箭入场 / 火箭发射→卫星入轨 / 载荷分离→卫星对接 / 卫星再入→天文台 /
 * 完整扫描动画（星图扫描态 + 恒星点亮 + 进度环接力 + 图标旋转）/ 图标收起展开。
 *
 * 这些动画都长在 MainActivity 的视图层级里（星图 / 火箭层 / 天文台层 / 图标），
 * 脱离主页单独放会走样，所以预览 = 带着 {@link MainActivity#EXTRA_ANIM_DEMO} 启动主页。
 */
public class AnimationPreviewActivity extends BaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_animation_preview);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        bind("observatory", R.id.demoObservatory);
        bind("rocket", R.id.demoRocket);
        bind("launch", R.id.demoLaunch);
        bind("dock", R.id.demoDock);
        bind("reentry", R.id.demoReentry);
        bind("scan", R.id.demoScan);
        bind("icon", R.id.demoIcon);
    }

    /** 条目 → 启动一个独立的预览实例单播对应动画；实例播完按「退出预览」finish 回到本清单。 */
    private void bind(String demoKey, int buttonId) {
        findViewById(buttonId).setOnClickListener(v -> {
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra(MainActivity.EXTRA_ANIM_DEMO, demoKey);
            startActivity(i);
        });
    }
}
