package com.mqclient.ibmmq.pcf

import com.ibm.mq.constants.MQConstants
import kotlin.test.Test

/**
 * 诊断（临时）：dump 客户端里 CLUSTER 相关常量的实际数值 —— 集群关系查询返回
 * 3015（MQRCCF_CFST_PARM_ID_ERROR，字符串参数 ID 无效），核对常量值定位。
 */
class ClusterConstantsDiagTest {

    @Test
    fun dumpClusterConstants() {
        MQConstants::class.java.fields
            .filter { it.name.contains("CLUSTER") || it.name.contains("REPOS") }
            .sortedBy { it.name }
            .forEach { field ->
                val value = runCatching { field.get(null) }.getOrNull()
                println("DIAG ${field.name} = $value")
            }
        // 两个命令常量单独确认
        println("DIAG MQCMD_INQUIRE_CLUSTER_Q_MGR = ${MQConstants.MQCMD_INQUIRE_CLUSTER_Q_MGR}")
        println("DIAG MQCMD_INQUIRE_Q_MGR = ${MQConstants.MQCMD_INQUIRE_Q_MGR}")
        println("DIAG MQCA_CLUSTER_NAME = ${MQConstants.MQCA_CLUSTER_NAME}")
        println("DIAG MQCA_Q_MGR_NAME = ${MQConstants.MQCA_Q_MGR_NAME}")
        println("DIAG MQCA_REPOSITORY_NAME = ${MQConstants.MQCA_REPOSITORY_NAME}")
    }
}
