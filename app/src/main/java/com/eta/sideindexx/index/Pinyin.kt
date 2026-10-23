package com.eta.sideindexx.index

/** 取标签首字母（A-Z / #），用于列表排序。字母索引 UI 已移除，仅保留排序所需的这一个函数。 */
object Pinyin {

    private val COMMON = mapOf(
        '阿' to 'A', '安' to 'A', '白' to 'B', '百' to 'B', '北' to 'B', '本' to 'B', '笔' to 'B', '便' to 'B',
        '菜' to 'C', '茶' to 'C', '超' to 'C', '车' to 'C', '成' to 'C', '程' to 'C', '城' to 'C', '充' to 'C',
        '大' to 'D', '单' to 'D', '当' to 'D', '导' to 'D', '灯' to 'D', '地' to 'D', '点' to 'D', '电' to 'D',
        '抖' to 'D', '短' to 'D', '对' to 'D', '多' to 'D',
        '二' to 'E', '发' to 'F', '方' to 'F', '飞' to 'F', '分' to 'F', '风' to 'F', '服' to 'F', '付' to 'F',
        '高' to 'G', '谷' to 'G', '管' to 'G', '光' to 'G', '广' to 'G', '国' to 'G',
        '海' to 'H', '好' to 'H', '和' to 'H', '黑' to 'H', '红' to 'H', '花' to 'H', '欢' to 'H', '会' to 'H',
        '火' to 'H',
        '基' to 'J', '机' to 'J', '极' to 'J', '记' to 'J', '家' to 'J', '剪' to 'J', '简' to 'J', '健' to 'J',
        '交' to 'J', '教' to 'J', '街' to 'J', '金' to 'J', '京' to 'J', '精' to 'J', '九' to 'J', '就' to 'J',
        '卡' to 'K', '看' to 'K', '康' to 'K', '科' to 'K', '可' to 'K', '空' to 'K', '快' to 'K',
        '来' to 'L', '蓝' to 'L', '老' to 'L', '乐' to 'L', '类' to 'L', '理' to 'L', '连' to 'L', '聊' to 'L',
        '流' to 'L', '录' to 'L', '旅' to 'L', '绿' to 'L',
        '马' to 'M', '买' to 'M', '猫' to 'M', '美' to 'M', '密' to 'M', '面' to 'M', '明' to 'M', '魔' to 'M',
        '木' to 'M', '幕' to 'M',
        '内' to 'N', '你' to 'N', '年' to 'N', '牛' to 'N',
        '排' to 'P', '片' to 'P', '苹' to 'P', '屏' to 'P',
        '其' to 'Q', '企' to 'Q', '气' to 'Q', '前' to 'Q', '清' to 'Q', '全' to 'Q', '趣' to 'Q',
        '人' to 'R', '日' to 'R', '软' to 'R',
        '赛' to 'S', '三' to 'S', '色' to 'S', '商' to 'S', '上' to 'S', '设' to 'S', '深' to 'S', '神' to 'S',
        '声' to 'S', '生' to 'S', '时' to 'S', '识' to 'S', '视' to 'S', '手' to 'S', '书' to 'S', '数' to 'S',
        '水' to 'S', '搜' to 'S', '苏' to 'S', '素' to 'S',
        '台' to 'T', '太' to 'T', '淘' to 'T', '天' to 'T', '通' to 'T', '图' to 'T',
        '外' to 'W', '完' to 'W', '网' to 'W', '微' to 'W', '卫' to 'W', '文' to 'W', '我' to 'W', '无' to 'W',
        '西' to 'X', '喜' to 'X', '下' to 'X', '先' to 'X', '相' to 'X', '小' to 'X', '笑' to 'X',
        '新' to 'X', '信' to 'X', '星' to 'X', '讯' to 'X',
        '鸭' to 'Y', '亚' to 'Y', '一' to 'Y', '医' to 'Y', '移' to 'Y', '音' to 'Y', '引' to 'Y', '应' to 'Y',
        '用' to 'Y', '有' to 'Y', '娱' to 'Y', '语' to 'Y', '云' to 'Y',
        '在' to 'Z', '赞' to 'Z', '早' to 'Z', '支' to 'Z', '知' to 'Z', '中' to 'Z', '终' to 'Z', '主' to 'Z',
        '转' to 'Z', '字' to 'Z', '自' to 'Z'
    )

    fun initial(label: String?): String {
        val s = label?.trim().orEmpty()
        if (s.isEmpty()) return "#"
        val c = s[0]
        if (c in 'A'..'Z' || c in 'a'..'z') return c.uppercase()
        if (c in '0'..'9') return "#"
        return (COMMON[c] ?: '#').toString()
    }
}
