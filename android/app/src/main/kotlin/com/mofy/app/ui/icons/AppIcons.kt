package com.mofy.app.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.mofy.app.R

/**
 * Local replacement for material-icons-extended's Icons.Filled.* - each
 * property here resolves to a res/drawable/ic_*.xml VectorDrawable (fetched
 * from fonts.google.com/icons, Android XML export) instead of a dependency
 * that bundles every Material icon (~thousands of unused classes in every
 * release build). Only the icons this app actually uses are declared here.
 *
 * @Composable get() (not a plain val) - ImageVector.vectorResource() needs
 * LocalContext, which is only available inside composition.
 */
object AppIcons {
    val Add: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_add)
    val ArrowBack: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_arrow_back)
    val ArrowBackAutoMirrored: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_arrow_back_automirrored)
    val Check: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_check)
    val ChevronRight: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_chevron_right)
    val Close: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_close)
    val Delete: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_delete)
    val Edit: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_edit)
    val Explore: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_explore)
    val FilterList: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_filter_list)
    val Forward10: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_forward_10)
    val Groups: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_groups)
    val Home: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_home)
    val Movie: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_movie)
    val Pause: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_pause)
    val People: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_people)
    val PlayArrow: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_play_arrow)
    val Replay10: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_replay_10)
    val Search: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_search)
    val Settings: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_settings)
    val Star: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_star)
    val Sync: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sync)
    val ThumbDown: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_thumb_down)
    val ThumbUp: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_thumb_up)
    val VideoLibrary: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_video_library)
    val Whatshot: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_whatshot)
}
