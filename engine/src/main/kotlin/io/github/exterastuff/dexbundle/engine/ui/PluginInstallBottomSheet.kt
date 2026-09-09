package io.github.exterastuff.dexbundle.engine.ui

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.Layout
import android.text.SpannableStringBuilder
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.exteragram.messenger.plugins.Plugin
import com.exteragram.messenger.plugins.PluginsController
import com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet.PluginInstallParams
import com.exteragram.messenger.utils.text.LocaleUtils
import io.github.exterastuff.dexbundle.engine.compat.ExteraConfigCompat
import io.github.exterastuff.dexbundle.engine.eject.EjectNotifier
import io.github.exterastuff.dexbundle.engine.i18n.Strings
import io.github.exterastuff.dexbundle.engine.impl.DexBundlePluginsEngine
import io.github.exterastuff.dexbundle.engine.util.runOnMainThread
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.CheckBox2
import org.telegram.ui.Components.EffectsTextView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.Stories.recorder.ButtonWithCounterView
import org.telegram.ui.Stories.recorder.HintView2

class PluginInstallBottomSheet(
    private val fragment: BaseFragment,
    private val plugin: Plugin,
    private val params: PluginInstallParams,
) : BottomSheet(fragment.parentActivity, false, fragment.resourceProvider),
    EjectNotifier.Delegate {

    companion object {
        private const val ICON_SIZE = 78

        private const val HINT_DIRECTION = 3

        private const val HINT_SHOWN_TRUSTED_KEY =
            "dexengine_plugin_source_hint_trusted"

        private const val HINT_SHOWN_UNKNOWN_KEY =
            "dexengine_plugin_source_hint_unknown"

        fun show(fragment: BaseFragment, plugin: Plugin, params: PluginInstallParams) =
            runOnMainThread {
                PluginInstallBottomSheet(fragment, plugin, params).show()
            }
    }

    private val unsubscribeFromEject = EjectNotifier.subscribe(this)

    private val isUpdate = PluginsController.getInstance()
        .plugins
        .containsKey(plugin.getId())

    private var enableAfterInstallation = false

    private var installing = false

    private var currentHint: HintView2? = null

    private lateinit var sourceChip: View

    private val button = ButtonWithCounterView(context, true, resourcesProvider).apply {
        setRound()
        setText(installButtonText(), false)
        setSubText(null, false)
        setOnClickListener { onInstallClick() }
    }

    init {
        setDelegate(object : BottomSheetDelegate() {
            override fun canDismiss(): Boolean = !installing
        })

        fixNavigationBar()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        content.addView(
            createIcon(),
            LayoutHelper.createLinear(
                ICON_SIZE,
                ICON_SIZE,
                Gravity.CENTER_HORIZONTAL,
                0f,
                28f,
                0f,
                0f
            )
        )

        content.addView(
            createTitle(),
            LayoutHelper.createLinear(MATCH_PARENT, WRAP_CONTENT, 0, 40f, 16f, 40f, 0f)
        )

        content.addView(
            createSubtitle(),
            LayoutHelper.createLinear(MATCH_PARENT, WRAP_CONTENT, 0, 21f, 4f, 21f, 0f)
        )

        content.addView(
            createSourceChip(),
            LayoutHelper.createLinear(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER, 0f, 12f, 0f, 0f)
        )

        content.addView(
            createDescription(),
            LayoutHelper.createLinear(MATCH_PARENT, WRAP_CONTENT, 0, 21f, 28f, 21f, 0f)
        )

        content.addView(button, LayoutHelper.createLinear(MATCH_PARENT, 48, 0, 16f, 28f, 16f, 16f))

        if (!plugin.isEnabled() && !ExteraConfigCompat.isPluginsSafeMode())
            content.addView(
                createEnableAfterInstallationCheckbox(),
                LayoutHelper.createLinear(
                    WRAP_CONTENT,
                    WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL,
                    0f,
                    0f,
                    0f,
                    8f
                )
            )

        setCustomView(ScrollView(context).apply { addView(content) })

        if (!wasSourceHintShown())
            AndroidUtilities.runOnUIThread({ sourceChip.performClick() }, 600)
    }

    private fun installButtonText(): String =
        if (isUpdate)
            Strings.updatePlugin()
        else
            Strings.installPlugin()

    private fun createIcon(): View {
        val pack = plugin.getPack()
        val index = plugin.getIndex()

        if (pack == null || index < 0)
            return createDefaultIcon()

        return BackupImageView(context).apply {
            imageReceiver.autoRepeat = 1

            MediaDataController.getInstance(UserConfig.selectedAccount)
                .setPlaceholderImageByIndex(this, pack, index, "${ICON_SIZE}_$ICON_SIZE")
        }
    }

    private fun createDefaultIcon(): View =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.plugins_filled)
            colorFilter = PorterDuffColorFilter(
                getThemedColor(Theme.key_featuredStickers_buttonText),
                PorterDuff.Mode.SRC_IN
            )
            background = Theme.createCircleDrawable(
                dp(ICON_SIZE.toFloat()),
                getThemedColor(Theme.key_featuredStickers_addButton)
            )

            val padding = dp(16f)
            setPadding(padding, padding, padding, padding)
        }

    private fun createTitle(): View =
        TextView(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = AndroidUtilities.bold()
            text = plugin.getName()
        }

    private fun createSubtitle(): View =
        EffectsTextView(context, fragment.resourceProvider).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            typeface = AndroidUtilities.regular()
            movementMethod = AndroidUtilities.LinkMovementMethodMy()
            setLinkTextColor(getThemedColor(Theme.key_dialogTextLink))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText))

            val subtitle = SpannableStringBuilder(Strings.pluginVersion())
                .append(' ')
                .append(plugin.getVersion())
                .append(" • ")
                .append(LocaleUtils.formatWithUsernames(plugin.getAuthor(), fragment) { dismiss() })

            text = subtitle
        }

    private fun createSourceChip(): View {
        val color = getThemedColor(
            if (params.trusted) Theme.key_windowBackgroundWhiteGreenText
            else Theme.key_text_RedRegular
        )

        val chip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = Theme.createRoundRectDrawable(
                dp(20f),
                dp(20f),
                AndroidUtilities.multiplyAlphaComponent(color, 0.1f)
            )
            setPadding(dp(12f), dp(6f), dp(16f), dp(6f))

            addView(
                ImageView(context).apply {
                    setImageResource(if (params.trusted) R.drawable.trusted_mini else R.drawable.unknown_mini)
                    colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
                },
                LayoutHelper.createLinear(14, 14, Gravity.CENTER_VERTICAL, 0f, 0f, 6f, 0f)
            )

            addView(
                TextView(context).apply {
                    typeface = AndroidUtilities.regular()
                    setTextColor(color)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    text = if (params.trusted)
                        Strings.sourceTrusted()
                    else
                        Strings.sourceUnknown()
                }
            )

            setOnClickListener { showSourceHint(it) }
        }

        ScaleStateListAnimator.apply(chip, 0.05f, 1.5f)

        sourceChip = chip
        return chip
    }

    private fun createEnableAfterInstallationCheckbox(): View {
        val checkBox = CheckBox2(context, 21, resourcesProvider).apply {
            setColor(
                Theme.key_radioBackgroundChecked,
                Theme.key_checkboxDisabled,
                Theme.key_checkboxCheck
            )
            drawUnchecked = true
            setChecked(enableAfterInstallation, false)
            setDrawBackgroundAsArc(10)
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8f), dp(6f), dp(10f), dp(6f))

            addView(
                FrameLayout(context).apply {
                    addView(
                        checkBox,
                        LayoutHelper.createFrame(21, 21f, Gravity.CENTER, 0f, 0f, 0f, 0f)
                    )
                },
                LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 0f, 0f, 6f, 0f)
            )

            addView(
                TextView(context).apply {
                    setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    text = Strings.enableAfterInstallation()
                },
                LayoutHelper.createLinear(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL)
            )

            background =
                Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), 8, 8)

            setOnClickListener {
                checkBox.setChecked(!checkBox.isChecked, true)
                enableAfterInstallation = checkBox.isChecked
            }
        }

        ScaleStateListAnimator.apply(row, 0.05f, 1.2f)

        return row
    }

    private fun createDescription(): View =
        EffectsTextView(context).apply {
            gravity = Gravity.START
            typeface = AndroidUtilities.regular()
            movementMethod = AndroidUtilities.LinkMovementMethodMy()
            setLinkTextColor(getThemedColor(Theme.key_dialogTextLink))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText))
            text = LocaleUtils.fullyFormatText(plugin.getDescription(), fragment) { dismiss() }
        }

    private fun wasSourceHintShown(): Boolean =
        ExteraConfigCompat.getPreferences().getBoolean(
            if (params.trusted) HINT_SHOWN_TRUSTED_KEY else HINT_SHOWN_UNKNOWN_KEY,
            false
        )

    private fun showSourceHint(anchor: View) {
        currentHint?.hide()

        val hint = HintView2(context, HINT_DIRECTION)
            .setMultilineText(true)
            .setBgColor(getThemedColor(Theme.key_undo_background))
            .setTextColor(getThemedColor(Theme.key_undo_infoColor))
            .setText(
                AndroidUtilities.replaceTags(
                    if (params.trusted)
                        Strings.sourceTrustedInfo()
                    else
                        Strings.sourceUnknownInfo()
                )
            )
            .setTextAlign(Layout.Alignment.ALIGN_CENTER)
            .allowBlur(true)
            .setRounding(12f)

        hint.setMaxWidthPx(HintView2.cutInFancyHalf(hint.text, hint.textPaint))

        container.addView(
            hint,
            LayoutHelper.createFrame(
                MATCH_PARENT,
                100f,
                Gravity.TOP or Gravity.FILL_HORIZONTAL,
                32f,
                0f,
                32f,
                0f
            )
        )
        currentHint = hint

        container.post {
            val anchorPosition = IntArray(2)
            anchor.getLocationInWindow(anchorPosition)

            val containerPosition = IntArray(2)
            container.getLocationInWindow(containerPosition)

            val x = (anchorPosition[0] - containerPosition[0]).toFloat()
            val y = anchorPosition[1] - containerPosition[1]

            hint.translationY = (y - dp(100f) - dp(6f)).toFloat()
            hint.setJointPx(0f, x + anchor.measuredWidth / 2f - dp(32f))
            hint.setDuration(5500)
            hint.show()
        }

        ExteraConfigCompat.getEditor()
            .putBoolean(
                if (params.trusted) HINT_SHOWN_TRUSTED_KEY else HINT_SHOWN_UNKNOWN_KEY,
                true
            )
            .apply()
    }

    private fun onInstallClick() {
        if (installing)
            return

        installing = true
        button.isLoading = true
        setCancelable(false)
        setCanDismissWithSwipe(false)
        setCanDismissWithTouchOutside(false)

        DexBundlePluginsEngine.install(params.filePath, plugin) { error ->
            runOnMainThread { onInstallFinished(error) }
        }
    }

    private fun onInstallFinished(error: String?) {
        installing = false
        button.isLoading = false
        setCancelable(true)
        setCanDismissWithSwipe(true)
        setCanDismissWithTouchOutside(true)

        if (error != null) {
            button.setText(installButtonText(), true)

            BulletinFactory.of(topBulletinContainer, resourcesProvider)
                .createSimpleBulletin(
                    R.raw.error,
                    Strings.installError(plugin.getName()),
                    LocaleUtils.createCopySpan(fragment)
                ) { copyToClipboard(error) }
                .show()

            return
        }

        dismiss()

        if (enableAfterInstallation && !ExteraConfigCompat.isPluginsSafeMode()) {
            PluginsController.getInstance().setPluginEnabled(plugin.getId(), true) { enableError ->
                if (enableError == null)
                    showSuccessBulletin()
                else
                    BulletinFactory.of(fragment)
                        .createSimpleBulletin(
                            R.raw.error,
                            Strings.installedButFailedToEnable(plugin.getName()),
                            LocaleUtils.createCopySpan(fragment)
                        ) { copyToClipboard(enableError) }
                        .show()
            }

            return
        }

        showSuccessBulletin()
    }

    private fun showSuccessBulletin() {
        val message = if (isUpdate)
            Strings.updated(plugin.getName())
        else
            Strings.installed(plugin.getName())

        BulletinFactory.of(fragment)
            .createSimpleBulletin(R.raw.contact_check, message)
            .show()
    }

    private fun copyToClipboard(text: String) {
        if (AndroidUtilities.addToClipboard(text))
            BulletinFactory.of(fragment)
                .createCopyBulletin(Strings.textCopied())
                .show()
    }

    override fun onSwipeStarts() {
        currentHint?.hide()
        currentHint = null
    }

    override fun dismiss() {
        currentHint?.hide()
        currentHint = null

        super.dismiss()
    }

    override fun onStop() {
        unsubscribeFromEject()
        super.onStop()
    }

    override fun onEject() = runOnMainThread { dismiss() }
}
