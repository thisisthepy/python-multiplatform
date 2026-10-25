package fixture.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What `MemberResolverComposeTest` compares a Python-built chain against. Compose's own structural
 * equality, so the chain cannot match unless Compose's `padding` and `fillMaxWidth` really ran.
 */
fun equalsPaddingThenFillMaxWidth(modifier: Modifier, pad: Double): Boolean =
    modifier == Modifier.padding(pad.dp).fillMaxWidth()

/** The negative control: the same members in the other order is a different chain. */
fun equalsFillMaxWidthThenPadding(modifier: Modifier, pad: Double): Boolean =
    modifier == Modifier.fillMaxWidth().padding(pad.dp)
