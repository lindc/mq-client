plugins {
    id("mq.jvm")
}

dependencies {
    // 图模型直接承载 kuiver 的 Kuiver / KuiverEdge / KuiverNode —— 图数据
    // 结构不重复发明，本模块只补「统一节点词汇 + 组装产物」这一层契约。
    api(libs.kuiver)
    testImplementation(kotlin("test"))
}
