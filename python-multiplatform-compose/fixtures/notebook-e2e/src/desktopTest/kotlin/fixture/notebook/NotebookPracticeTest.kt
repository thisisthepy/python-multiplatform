package fixture.notebook

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fixture.notebook.NotebookHost.cell
import fixture.notebook.NotebookHost.inkOf
import fixture.notebook.NotebookRootTest.Companion.CELL_05
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The notebook's "UI 실습" section (cells 18-40): each practice cell redeclares the root, and the screen
 * must then show exactly what the same composables draw from Kotlin. One test per cell, so a regression
 * fails under the cell's number.
 *
 * Every cell is written in the spellings pythonx-compose decided, which differ from the notebook's text
 * (pythonx-compose INTENT 2.4 and 5): `@app` instead of `main.App.update(...)`, Kotlin's own parameters
 * snake-cased (`on_click`, `image_vector`, `tint`, `content_description`), `Color(0xFF...)` instead of a
 * raw integer, `TextUnit(30, TextUnitType.Sp)` for a font size (a `TextUnit` refuses a raw number),
 * `ButtonColors(...)` / `CardColors(...)` -- Kotlin's own classes -- for the notebook's `color=`,
 * `Spacer(modifier=Modifier.padding(...))` instead of `Spacer(top=...)`, and `DefaultIcons.Add` read
 * without parentheses. What the notebook writes and Kotlin has no counterpart for is listed in this
 * module's README under "Gaps" and is left out of the cell rather than imitated.
 */
class NotebookPracticeTest {

    @BeforeTest
    fun host() = NotebookHost.install()

    /** Cell 19: a red `Text`. */
    @Test
    fun cell19_text() = assertCellDraws(
        "19",
        """
        from pythonx.compose.ui.graphics import Color

        @app
        @Composable
        def Practice():
            Text("나는 그냥 텍스트랍니다.", color=Color(0xFFFF0000))
        """,
    ) {
        Text("나는 그냥 텍스트랍니다.", color = Color(0xFFFF0000))
    }

    /** Cell 20: the same, at `font_size=30`. */
    @Test
    fun cell20_textWithAFontSize() = assertCellDraws(
        "20",
        """
        from pythonx.compose.ui.graphics import Color
        from pythonx.compose.ui.unit import TextUnit, TextUnitType

        @app
        @Composable
        def Practice():
            Text("나는 그냥 텍스트랍니다. 좀 더 커져봤어요.", color=Color(0xFFFF0000), font_size=TextUnit(30, TextUnitType.Sp))
        """,
    ) {
        Text("나는 그냥 텍스트랍니다. 좀 더 커져봤어요.", color = Color(0xFFFF0000), fontSize = 30.sp)
    }

    /** Cell 22: a black `Button` with nothing inside. (`corner_radius=50` is a gap, see the README.) */
    @Test
    fun cell22_anEmptyBlackButton() = assertCellDraws(
        "22",
        """
        from pythonx.compose.ui.graphics import Color
        from pythonx.compose.material3 import ButtonColors

        @app
        @Composable
        def Practice():  # 내부 구성요소가 없는 버튼
            Button(
                on_click=lambda: None,
                colors=ButtonColors(container_color=Color(0xFF000000), content_color=Color(0xFFFFFFFF),
                                    disabled_container_color=Color(0xFF000000), disabled_content_color=Color(0xFFFFFFFF)),
                content=lambda: None,
            )
        """,
    ) {
        Button(onClick = {}, colors = blackButton()) {}
    }

    /** Cell 23: the same button, holding cell 20's text. */
    @Test
    fun cell23_aButtonHoldingText() = assertCellDraws(
        "23",
        """
        from pythonx.compose.ui.graphics import Color
        from pythonx.compose.ui.unit import TextUnit, TextUnitType
        from pythonx.compose.material3 import ButtonColors

        @app
        @Composable
        def Practice():  # 텍스트가 하나 있는 버튼
            Button(
                on_click=lambda: None,
                colors=ButtonColors(container_color=Color(0xFF000000), content_color=Color(0xFFFFFFFF),
                                    disabled_container_color=Color(0xFF000000), disabled_content_color=Color(0xFFFFFFFF)),
                content=lambda: Text("나는 그냥 텍스트랍니다. 좀 더 커져봤어요.", color=Color(0xFFFF0000), font_size=TextUnit(30, TextUnitType.Sp))
            )
        """,
    ) {
        Button(onClick = {}, colors = blackButton()) {
            Text("나는 그냥 텍스트랍니다. 좀 더 커져봤어요.", color = Color(0xFFFF0000), fontSize = 30.sp)
        }
    }

    /** Cell 25: a red `Card` with white text. (`modifier=modifier` and `corner_radius=20` are gaps.) */
    @Test
    fun cell25_aCard() = assertCellDraws(
        "25",
        """
        from pythonx.compose.ui.graphics import Color
        from pythonx.compose.material3 import CardColors

        @app
        @Composable
        def Practice():
            Card(
                colors=CardColors(container_color=Color(0xFFFF0000), content_color=Color(0xFFFFFFFF),
                                  disabled_container_color=Color(0xFFFF0000), disabled_content_color=Color(0xFFFFFFFF)),
                content=lambda: Text("나는 그냥 텍스트랍니다.", color=Color(0xFFFFFFFF))
            )
        """,
    ) {
        Card(colors = CardColors(Color(0xFFFF0000), Color(0xFFFFFFFF), Color(0xFFFF0000), Color(0xFFFFFFFF))) {
            Text("나는 그냥 텍스트랍니다.", color = Color(0xFFFFFFFF))
        }
    }

    /** Cell 28: `DefaultIcons.Add` is Compose's `ImageVector` -- read, not called. */
    @Test
    fun cell28_defaultIconsAddIsAnImageVector() {
        cell(CELL_05)
        cell(
            """
            _nb_icon = DefaultIcons.Add
            assert type(_nb_icon)._kotlin_type_name == 'androidx.compose.ui.graphics.vector.ImageVector', type(_nb_icon)
            """,
        )
    }

    /** Cell 29: a red `Add` icon. The notebook's `icon=`/`color=` are Kotlin's `image_vector=`/`tint=`. */
    @Test
    fun cell29_anIcon() = assertCellDraws(
        "29",
        """
        from pythonx.compose.ui.graphics import Color

        @app
        @Composable
        def Practice():  # 아이콘
            Icon(
                image_vector=DefaultIcons.Add,
                content_description=None,
                tint=Color(0xFFFF0000)
            )
        """,
    ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFFFF0000))
    }

    /** Cell 30: a yellow `Add` icon inside a black button. */
    @Test
    fun cell30_anIconInsideAButton() = assertCellDraws(
        "30",
        """
        from pythonx.compose.ui.graphics import Color
        from pythonx.compose.material3 import ButtonColors

        @app
        @Composable
        def Practice():  # 버튼 안에 아이콘
            Button(
                on_click=lambda: None,
                colors=ButtonColors(container_color=Color(0xFF000000), content_color=Color(0xFFFFFFFF),
                                    disabled_container_color=Color(0xFF000000), disabled_content_color=Color(0xFFFFFFFF)),
                content=lambda: {
                    Icon(
                        image_vector=DefaultIcons.Add,
                        content_description=None,
                        tint=Color(0xFFFFFF00)
                    )
                }
            )
        """,
    ) {
        Button(onClick = {}, colors = blackButton()) {
            Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFFFFFF00))
        }
    }

    /** Cell 35: a `Column` of a small and a large button. */
    @Test
    fun cell35_aColumnOfTwoButtons() = assertCellDraws(
        "35",
        """
        from pythonx.compose.ui.graphics import Color
        from pythonx.compose.ui.unit import TextUnit, TextUnitType
        from pythonx.compose.material3 import ButtonColors

        def _black():
            return ButtonColors(container_color=Color(0xFF000000), content_color=Color(0xFFFFFFFF),
                                disabled_container_color=Color(0xFF000000), disabled_content_color=Color(0xFFFFFFFF))

        @app
        @Composable
        def Practice():  # 세로 정렬
            Column(
                content=lambda: {
                    Button(
                        on_click=lambda: None,
                        colors=_black(),
                        content=lambda: Text("작은 버튼", color=Color(0xFFFFFFFF), font_size=TextUnit(10, TextUnitType.Sp))
                    ),
                    Button(
                        on_click=lambda: None,
                        colors=_black(),
                        content=lambda: Text("큰 버튼", color=Color(0xFFFFFFFF), font_size=TextUnit(30, TextUnitType.Sp))
                    )
                }
            )
        """,
        height = 200,
    ) {
        Column {
            Button(onClick = {}, colors = blackButton()) { Text("작은 버튼", color = Color(0xFFFFFFFF), fontSize = 10.sp) }
            Button(onClick = {}, colors = blackButton()) { Text("큰 버튼", color = Color(0xFFFFFFFF), fontSize = 30.sp) }
        }
    }

    /** Cell 36: a right-aligned `Column`, in the notebook's grouped spelling `Alignment.Horizontal.End`. */
    @Test
    fun cell36_aColumnAlignedToTheEnd() = assertCellDraws(
        "36",
        """
        from pythonx.compose.ui.graphics import Color
        from pythonx.compose.ui.unit import TextUnit, TextUnitType

        @app
        @Composable
        def Practice():  # 세로 정렬 + 오른쪽 정렬
            Column(
                horizontal_alignment=Alignment.Horizontal.End,
                content=lambda: {
                    Text("작은 글자", color=Color(0xFF000000), font_size=TextUnit(10, TextUnitType.Sp)),
                    Text("큰 글자", color=Color(0xFF000000), font_size=TextUnit(30, TextUnitType.Sp))
                }
            )
        """,
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Text("작은 글자", color = Color(0xFF000000), fontSize = 10.sp)
            Text("큰 글자", color = Color(0xFF000000), fontSize = 30.sp)
        }
    }

    /**
     * Cell 37's signature: the notebook documents `Row(horizontal_arrangement=Arrangement.Center ...)`;
     * no code cell passes one, so this is the one Arrangement scenario the notebook states. The row is
     * given a width so that centring is visible.
     */
    @Test
    fun cell37_aRowWithAnArrangement() = assertCellDraws(
        "37",
        """
        @app
        @Composable
        def Practice():
            Row(
                modifier=Modifier.width(300),
                horizontal_arrangement=Arrangement.Center,
                content=lambda: {
                    Text("작은 글자"),
                    Text("큰 글자")
                }
            )
        """,
    ) {
        Row(modifier = Modifier.width(300.dp), horizontalArrangement = Arrangement.Center) {
            Text("작은 글자")
            Text("큰 글자")
        }
    }

    /** Cell 38: a `Row` of a small and a large text. */
    @Test
    fun cell38_aRow() = assertCellDraws(
        "38",
        """
        from pythonx.compose.ui.graphics import Color
        from pythonx.compose.ui.unit import TextUnit, TextUnitType

        @app
        @Composable
        def Practice():  # 가로 정렬
            Row(
                content=lambda: {
                    Text("작은 글자", color=Color(0xFF000000), font_size=TextUnit(10, TextUnitType.Sp)),
                    Text("큰 글자", color=Color(0xFF000000), font_size=TextUnit(30, TextUnitType.Sp))
                }
            )
        """,
    ) {
        Row {
            Text("작은 글자", color = Color(0xFF000000), fontSize = 10.sp)
            Text("큰 글자", color = Color(0xFF000000), fontSize = 30.sp)
        }
    }

    /** Cell 40: cell 25's card again, its text spaced by `Spacer`s -- `Spacer(modifier=Modifier.padding(...))`. */
    @Test
    fun cell40_aCardSpacedBySpacers() = assertCellDraws(
        "40",
        """
        from pythonx.compose.ui.graphics import Color
        from pythonx.compose.material3 import CardColors

        @app
        @Composable
        def Practice():  # 아까 카드에 여백 넣어보기
            Card(
                colors=CardColors(container_color=Color(0xFF000000), content_color=Color(0xFFFFFFFF),
                                  disabled_container_color=Color(0xFF000000), disabled_content_color=Color(0xFFFFFFFF)),
                content=lambda: {
                    Column(
                        content=lambda: {
                            Spacer(modifier=Modifier.padding(top=10)),
                            Row(
                                content=lambda: {
                                    Spacer(modifier=Modifier.padding(start=20)),
                                    Text("나는 그냥 텍스트랍니다.", color=Color(0xFFFFFFFF)),
                                    Spacer(modifier=Modifier.padding(end=20))
                                }
                            ),
                            Spacer(modifier=Modifier.padding(bottom=10))
                        }
                    )
                }
            )
        """,
    ) {
        Card(colors = CardColors(Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFFFFFFFF))) {
            Column {
                Spacer(modifier = Modifier.padding(top = 10.dp))
                Row {
                    Spacer(modifier = Modifier.padding(start = 20.dp))
                    Text("나는 그냥 텍스트랍니다.", color = Color(0xFFFFFFFF))
                    Spacer(modifier = Modifier.padding(end = 20.dp))
                }
                Spacer(modifier = Modifier.padding(bottom = 10.dp))
            }
        }
    }

    /**
     * Runs cell 5 and then [source] against the live host, and requires the screen to equal [control]
     * within [NotebookHost.MAX_FRAMES] frames.
     */
    private fun assertCellDraws(
        cellNumber: String,
        source: String,
        height: Int = NotebookRootTest.H,
        control: @Composable () -> Unit,
    ) {
        val width = NotebookRootTest.W
        val expected = NotebookHost.controlPixels(width, height, control)
        assertTrue(inkOf(expected) > 0, "cell $cellNumber: the Kotlin control drew nothing, so the comparison measures nothing")
        val scene = NotebookHost.hostScene(width, height)
        try {
            scene.render()
            val seen = NotebookHost.redeclare(scene, CELL_05 + source.trimIndent(), expected)
            println("notebook cell $cellNumber: ${seen.describe()}")
            assertTrue(seen.matchesExpected, "cell $cellNumber: the screen is not what the cell declares: ${seen.describe()}")
        } finally {
            scene.close()
        }
    }
}

private fun blackButton() = ButtonColors(Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFFFFFFFF))
