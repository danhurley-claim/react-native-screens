package com.swmansion.rnscreens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.AnimationUtils
import android.view.animation.Transformation
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.commit
import com.facebook.react.uimanager.PixelUtil
import com.facebook.react.uimanager.PointerEvents
import com.facebook.react.uimanager.ReactPointerEventsView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.swmansion.rnscreens.bottomsheet.DimmingFragment
import com.swmansion.rnscreens.ext.recycle
import com.swmansion.rnscreens.utils.DeviceUtils
import kotlin.math.max

sealed class KeyboardState()
object KeyboardNotVisible : KeyboardState()
object KeyboardDidHide : KeyboardState()
class KeyboardVisible(val height: Int) : KeyboardState()

class ScreenStackFragment : ScreenFragment, ScreenStackFragmentWrapper {
    private var appBarLayout: AppBarLayout? = null
    private var toolbar: Toolbar? = null
    private var isToolbarShadowHidden = false
    private var isToolbarTranslucent = false

    private var lastFocusedChild: View? = null

    var searchView: CustomSearchView? = null
    var onSearchViewCreate: ((searchView: CustomSearchView) -> Unit)? = null

    private lateinit var coordinatorLayout: ScreensCoordinatorLayout

    private val screenStack: ScreenStack
        get() {
            val container = screen.container
            check(container is ScreenStack) { "ScreenStackFragment added into a non-stack container" }
            return container
        }

    @SuppressLint("ValidFragment")
    constructor(screenView: Screen) : super(screenView)

    constructor() {
        throw IllegalStateException(
            "ScreenStack fragments should never be restored. Follow instructions from https://github.com/software-mansion/react-native-screens/issues/17#issuecomment-424704067 to properly configure your main activity."
        )
    }

    override fun removeToolbar() {
        appBarLayout?.let {
            toolbar?.let { toolbar ->
                if (toolbar.parent === it) {
                    it.removeView(toolbar)
                }
            }
        }
        toolbar = null
    }

    override fun setToolbar(toolbar: Toolbar) {
        appBarLayout?.addView(toolbar)
        toolbar.layoutParams = AppBarLayout.LayoutParams(
            AppBarLayout.LayoutParams.MATCH_PARENT, AppBarLayout.LayoutParams.WRAP_CONTENT
        ).apply { scrollFlags = 0 }
        this.toolbar = toolbar
    }

    override fun setToolbarShadowHidden(hidden: Boolean) {
        if (isToolbarShadowHidden != hidden) {
            appBarLayout?.targetElevation = if (hidden) 0f else PixelUtil.toPixelFromDIP(4f)
            isToolbarShadowHidden = hidden
        }
    }

    override fun setToolbarTranslucent(translucent: Boolean) {
        if (isToolbarTranslucent != translucent) {
            val params = screen.layoutParams
            (params as CoordinatorLayout.LayoutParams).behavior =
                if (translucent) null else ScrollingViewBehavior()
            isToolbarTranslucent = translucent
        }
    }

    override fun onContainerUpdate() {
        super.onContainerUpdate()
        screen.headerConfig?.onUpdate()
    }

    override fun onViewAnimationEnd() {
        super.onViewAnimationEnd()
        notifyViewAppearTransitionEnd()
    }

    private fun notifyViewAppearTransitionEnd() {
        val screenStack = view?.parent
        if (screenStack is ScreenStack) {
            screenStack.onViewAppearTransitionEnd()
        }
    }

    private val bottomSheetOnSwipedDownCallback = object : BottomSheetCallback() {
        override fun onStateChanged(bottomSheet: View, newState: Int) {
            if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                if (this@ScreenStackFragment.parentFragment is DimmingFragment) {
                    parentFragmentManager.commit {
                        setReorderingAllowed(true)
                        remove(this@ScreenStackFragment)
                    }
                } else {
                    this@ScreenStackFragment.dismissFromContainer()
                }
            }
            Log.i("ScreenStackFragment", "Sheet state changed to $newState, sheetSize: ${bottomSheet.width}, ${bottomSheet.height}")
        }

        override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
    }

    override fun onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation? {
        if (screen.stackPresentation != Screen.StackPresentation.FORM_SHEET) {
            return null
        }
        return if (enter) {
            AnimationUtils.loadAnimation(context, R.anim.rns_slide_in_from_bottom)
        } else {
            AnimationUtils.loadAnimation(context, R.anim.rns_slide_out_to_bottom)
        }
//        return super.onCreateAnimation(transit, enter, nextAnim)
    }

    internal fun onSheetCornerRadiusChange() {
        (screen.background as MaterialShapeDrawable).shapeAppearanceModel = ShapeAppearanceModel.Builder().apply {
            setTopLeftCorner(CornerFamily.ROUNDED, screen.sheetCornerRadius ?: 0F)
            setTopRightCorner(CornerFamily.ROUNDED, screen.sheetCornerRadius ?: 0F)
        }.build()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        coordinatorLayout = ScreensCoordinatorLayout(requireContext(), this).apply {
//            setBackgroundColor(Color.argb(0, 0, 0, 0))
        }

        screen.layoutParams = CoordinatorLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
        ).apply {
            behavior = if (screen.stackPresentation == Screen.StackPresentation.FORM_SHEET) {
                createAndConfigureBottomSheetBehaviour()
            } else if (isToolbarTranslucent) {
                null
            } else {
                ScrollingViewBehavior()
            }
        }

        if (screen.stackPresentation == Screen.StackPresentation.FORM_SHEET) {
            screen.clipToOutline = true
            attachShapeToScreen(screen) // TODO(@kkafar): without this line there is no drawable / outline & nothing shows...? Determine what's going on here
            screen.elevation = screen.sheetElevation
//            screen.outlineProvider = CustomOutlineProvider(PixelUtil.toPixelFromDIP(screen.sheetCornerRadius ?: 0F))

//            if (screen.isSheetGrabberVisible) {
//                val grabberView = BottomSheetDragHandleView(requireContext()).apply {
//                    layoutParams = ViewGroup.LayoutParams(
//                        ViewGroup.LayoutParams.MATCH_PARENT,
//                        ViewGroup.LayoutParams.WRAP_CONTENT
//                    )
//                }
//                coordinatorLayout.addView(grabberView)
//            }
        }

        coordinatorLayout.addView(screen.recycle())

        if (screen.stackPresentation != Screen.StackPresentation.MODAL && screen.stackPresentation != Screen.StackPresentation.FORM_SHEET) {
            appBarLayout = context?.let { AppBarLayout(it) }?.apply {
                // By default AppBarLayout will have a background color set but since we cover the whole layout
                // with toolbar (that can be semi-transparent) the bar layout background color does not pay a
                // role. On top of that it breaks screens animations when alfa offscreen compositing is off
                // (which is the default)
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams = AppBarLayout.LayoutParams(
                    AppBarLayout.LayoutParams.MATCH_PARENT, AppBarLayout.LayoutParams.WRAP_CONTENT
                )
            }

            coordinatorLayout.addView(appBarLayout)
            if (isToolbarShadowHidden) {
                appBarLayout?.targetElevation = 0f
            }
            toolbar?.let { appBarLayout?.addView(it.recycle()) }
            setHasOptionsMenu(true)
        }
        return coordinatorLayout
    }

    fun tryResolveWindowHeight(): Int? {
        // Note that these three values differ slightly! For now, for practical purposes
        // this is acceptable.
        if (screen.container != null) {
            return screenStack.height
        }

        val height1 = context?.resources?.displayMetrics?.heightPixels
        if (height1 != null) return height1

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val height2 = (context?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.currentWindowMetrics?.bounds?.height()
            if (height2 != null) return height2
        }

        return null
    }

    private val keyboardSheetCallback = object : BottomSheetCallback() {
        @RequiresApi(Build.VERSION_CODES.M)
        override fun onStateChanged(bottomSheet: View, newState: Int) {
            if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                val isKeyboardVisible = WindowInsetsCompat.toWindowInsetsCompat(bottomSheet.rootWindowInsets).isVisible(WindowInsetsCompat.Type.ime())
                if (isKeyboardVisible) {
                    // Does it not interfere with React Native focus mechanism? In any case I'm not aware
                    // of different way of hiding the keyboard.
                    // https://stackoverflow.com/questions/1109022/how-can-i-close-hide-the-android-soft-keyboard-programmatically
                    // https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility
                    if (bottomSheet.requestFocus()) {
                        val imm = requireContext().getSystemService(InputMethodManager::class.java)
                        imm.hideSoftInputFromWindow(bottomSheet.windowToken, 0)
                    }
                }
            }
        }

        override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit

    }

    internal fun configureBottomSheetBehaviour(behavior: BottomSheetBehavior<Screen>, keyboardState: KeyboardState = KeyboardNotVisible): BottomSheetBehavior<Screen> {
        val containerHeight = tryResolveWindowHeight()
        check(containerHeight != null) { "[RNScreens] Failed to find window height during bottom sheet behaviour configuration" }

        behavior.apply {
            isHideable = true
            isDraggable = true
            // Want to make sure that this callback is registered, but only once.
            // Removing not registered callback is safe, does not throw.
            removeBottomSheetCallback(bottomSheetOnSwipedDownCallback)
            addBottomSheetCallback(bottomSheetOnSwipedDownCallback)
        }

        return when (keyboardState) {
            is KeyboardNotVisible -> {
                if (screen.sheetDetents.count() == 1) {
                    behavior.apply {
                        state = BottomSheetBehavior.STATE_EXPANDED
                        skipCollapsed = true
                        isFitToContents = true
                        maxHeight = (screen.sheetDetents.first() * containerHeight).toInt()
                    }
                } else if (screen.sheetDetents.count() == 2) {
                    behavior.apply {
                        state = Screen.sheetStateFromDetentIndex(screen.sheetInitialDetentIndex, screen.sheetDetents.count())
                        skipCollapsed = false
                        isFitToContents = true
                        peekHeight = (screen.sheetDetents[0] * containerHeight).toInt()
                        maxHeight = (screen.sheetDetents[1] * containerHeight).toInt()
                    }
                } else {
                    behavior.apply {
                        state = Screen.sheetStateFromDetentIndex(screen.sheetInitialDetentIndex, screen.sheetDetents.count())
                        skipCollapsed = false
                        isFitToContents = false
                        peekHeight = (screen.sheetDetents[0] * containerHeight).toInt()
//                maxHeight = (screen.sheetDetents[2] * displayMetrics.heightPixels).toInt()
                        expandedOffset = ((1 - screen.sheetDetents[2]) * containerHeight).toInt()
                        halfExpandedRatio = (screen.sheetDetents[1] / screen.sheetDetents[2]).toFloat()
                    }
                }
            }
            is KeyboardVisible -> {
                behavior.apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = false
                    isFitToContents = true
                    maxHeight = max(1, maxHeight - keyboardState.height)
                    addBottomSheetCallback(keyboardSheetCallback)
                }
            }
            is KeyboardDidHide -> {
                behavior.removeBottomSheetCallback(keyboardSheetCallback)
                if (screen.sheetDetents.count() == 1) {
                    behavior.apply {
//                        state = BottomSheetBehavior.STATE_EXPANDED
                        skipCollapsed = true
                        isFitToContents = true
                        maxHeight = (screen.sheetDetents.first() * containerHeight).toInt()
                    }
                } else if (screen.sheetDetents.count() == 2) {
                    // Here we assume that the keyboard was either closed explicitly by user,
                    // or the user dragged the sheet down. In any case the state should
                    // stay unchanged.
                    behavior.apply {
//                        state = BottomSheetBehavior.STATE_EXPANDED
                        skipCollapsed = false
                        isFitToContents = true
                        peekHeight = (screen.sheetDetents[0] * containerHeight).toInt()
                        maxHeight = (screen.sheetDetents[1] * containerHeight).toInt()
                    }
                } else {
                    behavior.apply {
//                        state = BottomSheetBehavior.STATE_EXPANDED
                        skipCollapsed = false
                        isFitToContents = false
                        peekHeight = (screen.sheetDetents[0] * containerHeight).toInt()
//                maxHeight = (screen.sheetDetents[2] * displayMetrics.heightPixels).toInt()
                        expandedOffset = ((1 - screen.sheetDetents[2]) * containerHeight).toInt()
                        halfExpandedRatio = (screen.sheetDetents[1] / screen.sheetDetents[2]).toFloat()
                    }
                }
            }
        }
//        return behavior
    }

    internal fun createAndConfigureBottomSheetBehaviour(): BottomSheetBehavior<Screen> {
        return configureBottomSheetBehaviour(BottomSheetBehavior<Screen>())
    }

    private fun attachShapeToScreen(screen: Screen) {
        val cornerSize = PixelUtil.toPixelFromDIP(screen.sheetCornerRadius ?: 0F)
        val shapeAppearanceModel = ShapeAppearanceModel.Builder().apply {
            setTopLeftCorner(CornerFamily.ROUNDED, cornerSize)
            setTopRightCorner(CornerFamily.ROUNDED, cornerSize)
        }.build()
        val shape = MaterialShapeDrawable(shapeAppearanceModel)
        shape.setTint((screen.background as? ColorDrawable?)?.color ?: Color.TRANSPARENT)
        screen.background = shape

//        screen.background = GradientDrawable().apply {
//            shape = GradientDrawable.RECTANGLE
// //            cornerRadii = FloatArray(8) { i -> if (i < 4) cornerSize else 0F }
//            cornerRadius = cornerSize
//        }
        // TODO(@kkafar): It looks like this finally works with ReactViewBackgroundDrawable,
        // however it should also work with GradientDrawable, but for some reason it does not on API < 33
        // (tested on 31)
//        screen.background = ReactViewBackgroundDrawable(requireContext()).apply {
//            setRadius(cornerSize, 1)
//            setRadius(cornerSize, 0)
//        }
    }

    override fun onStop() {
        if (DeviceUtils.isPlatformAndroidTV(context)) {
            lastFocusedChild = findLastFocusedChild()
        }

        super.onStop()
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        updateToolbarMenu(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        updateToolbarMenu(menu)
        return super.onCreateOptionsMenu(menu, inflater)
    }

    private fun shouldShowSearchBar(): Boolean {
        val config = screen.headerConfig
        val numberOfSubViews = config?.configSubviewsCount ?: 0
        if (config != null && numberOfSubViews > 0) {
            for (i in 0 until numberOfSubViews) {
                val subView = config.getConfigSubview(i)
                if (subView.type == ScreenStackHeaderSubview.Type.SEARCH_BAR) {
                    return true
                }
            }
        }
        return false
    }

    private fun updateToolbarMenu(menu: Menu) {
        menu.clear()
        if (shouldShowSearchBar()) {
            val currentContext = context
            if (searchView == null && currentContext != null) {
                val newSearchView = CustomSearchView(currentContext, this)
                searchView = newSearchView
                onSearchViewCreate?.invoke(newSearchView)
            }
            menu.add("").apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                actionView = searchView
            }
        }
    }

    private fun findLastFocusedChild(): View? {
        var view: View? = screen
        while (view != null) {
            if (view.isFocused) return view
            view = if (view is ViewGroup) view.focusedChild else null
        }

        return null
    }

    override fun canNavigateBack(): Boolean {
        val container: ScreenContainer? = screen.container
        check(container is ScreenStack) { "ScreenStackFragment added into a non-stack container" }
        return if (container.rootScreen == screen) {
            // this screen is the root of the container, if it is nested we can check parent container
            // if it is also a root or not
            val parentFragment = parentFragment
            if (parentFragment is ScreenStackFragment) {
                parentFragment.canNavigateBack()
            } else {
                false
            }
        } else {
            true
        }
    }

    override fun dismissFromContainer() {
        screenStack.dismiss(this)
    }

    private class ScreensCoordinatorLayout(
        context: Context,
        private val fragment: ScreenStackFragment
//    ) : CoordinatorLayout(context), ReactCompoundViewGroup, ReactHitSlopView {
    ) : CoordinatorLayout(context), ReactPointerEventsView {

        private val animationListener: Animation.AnimationListener =
            object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation) {
                    fragment.onViewAnimationStart()
                }

                override fun onAnimationEnd(animation: Animation) {
                    fragment.onViewAnimationEnd()
                }

                override fun onAnimationRepeat(animation: Animation) {}
            }

        override fun startAnimation(animation: Animation) {
            // For some reason View##onAnimationEnd doesn't get called for
            // exit transitions so we explicitly attach animation listener.
            // We also have some animations that are an AnimationSet, so we don't wrap them
            // in another set since it causes some visual glitches when going forward.
            // We also set the listener only when going forward, since when going back,
            // there is already a listener for dismiss action added, which would be overridden
            // and also this is not necessary when going back since the lifecycle methods
            // are correctly dispatched then.
            // We also add fakeAnimation to the set of animations, which sends the progress of animation
            val fakeAnimation = ScreensAnimation(fragment).apply { duration = animation.duration }

            if (animation is AnimationSet && !fragment.isRemoving) {
                animation.apply {
                    addAnimation(fakeAnimation)
                    setAnimationListener(animationListener)
                }.also {
                    super.startAnimation(it)
                }
            } else {
                AnimationSet(true).apply {
                    addAnimation(animation)
                    addAnimation(fakeAnimation)
                    setAnimationListener(animationListener)
                }.also {
                    super.startAnimation(it)
                }
            }
        }

        /**
         * This method implements a workaround for RN's autoFocus functionality. Because of the way
         * autoFocus is implemented it dismisses soft keyboard in fragment transition
         * due to change of visibility of the view at the start of the transition. Here we override the
         * call to `clearFocus` when the visibility of view is `INVISIBLE` since `clearFocus` triggers the
         * hiding of the keyboard in `ReactEditText.java`.
         */
        override fun clearFocus() {
            if (visibility != INVISIBLE) {
                super.clearFocus()
            }
        }

//        override fun reactTagForTouch(touchX: Float, touchY: Float): Int {
//            throw IllegalStateException("Screen wrapper should never be asked for the view tag")
//        }
//
//        override fun interceptsTouchEvent(touchX: Float, touchY: Float): Boolean {
//            return false
//        }
//
//        override fun getHitSlopRect(): Rect? {
//            val screen: Screen = fragment.screen
// //            left – The X coordinate of the left side of the rectangle
// //            top – The Y coordinate of the top of the rectangle i
// //            right – The X coordinate of the right side of the rectangle
// //            bottom – The Y coordinate of the bottom of the rectangle
//            return Rect(screen.x.toInt(), -screen.y.toInt(), screen.x.toInt() + screen.width, screen.y.toInt() + screen.height)
//        }

        override fun getPointerEvents(): PointerEvents {
            return PointerEvents.BOX_NONE
        }
    }

    private class ScreensAnimation(private val mFragment: ScreenFragment) : Animation() {
        override fun applyTransformation(interpolatedTime: Float, t: Transformation) {
            super.applyTransformation(interpolatedTime, t)
            // interpolated time should be the progress of the current transition
            mFragment.dispatchTransitionProgressEvent(interpolatedTime, !mFragment.isResumed)
        }
    }
}
