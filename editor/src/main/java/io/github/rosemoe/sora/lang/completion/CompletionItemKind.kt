/*******************************************************************************
 *    sora-editor - the awesome code editor for Android
 *    https://github.com/Rosemoe/sora-editor
 *    Copyright (C) 2020-2024  Rosemoe
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 ******************************************************************************/

package io.github.rosemoe.sora.lang.completion

import io.github.rosemoe.sora.R


/**
 * Completion item kinds.
 */
enum class CompletionItemKind(
    val value: Int,
    val iconRes: Int,
) {
    Identifier(0, R.drawable.symbol_string),
    Text(0, R.drawable.symbol_string),
    Method(1, R.drawable.symbol_method),
    Function(2, R.drawable.symbol_method),
    Constructor(3, R.drawable.symbol_method),
    Field(4, R.drawable.symbol_field),
    Variable(5, R.drawable.symbol_variable),
    Class(6, R.drawable.symbol_class),
    Interface(7, R.drawable.symbol_interface),
    Module(8, R.drawable.symbol_namespace),
    Property(9, R.drawable.symbol_property),
    Unit(10, R.drawable.symbol_ruler),
    Value(11, R.drawable.symbol_enum),
    Enum(12, R.drawable.symbol_enum),
    Keyword(13, R.drawable.symbol_keyword),
    Snippet(14, R.drawable.symbol_snippet),
    Color(15, R.drawable.symbol_color),
    Reference(17, R.drawable.symbol_reference),
    File(16, R.drawable.symbol_class),
    Folder(18, R.drawable.symbol_folder),
    EnumMember(19, R.drawable.symbol_enum_member),
    Constant(20, R.drawable.symbol_constant),
    Struct(21, R.drawable.symbol_structure),
    Event(22, R.drawable.symbol_event),
    Operator(23, R.drawable.symbol_operator),
    TypeParameter(24, R.drawable.symbol_parameter),
    User(25, R.drawable.symbol_user),
    Issue(26, R.drawable.symbol_issue);
}