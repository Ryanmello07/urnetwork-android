package com.bringyour.network.utils

import com.bringyour.sdk.Float64List
import com.bringyour.sdk.IntList
import com.bringyour.sdk.StringList

val sdkStringListToList: (StringList?) -> List<String> = { list ->

    val arr = mutableListOf<String>()
    if (list != null) {
        val n = list.len()
        for (i in 0 until n) {
            arr.add(list.get(i))
        }
    }

    arr
}

val listToSdkStringList: (List<String>) -> StringList = { values ->

    val list = StringList()
    for (value in values) {
        list.add(value)
    }

    list
}

val sdkIntListToArray: (IntList) -> MutableList<Long> = { list ->

    val n = list.len()
    val arr = mutableListOf<Long>()

    for (i in 0 until n) {

        val item = list.get(i)
        arr.add(item)

    }

    arr
}

val sdkFloat64ListToArray: (Float64List) -> MutableList<Double> = { list ->

    val n = list.len()
    val arr = mutableListOf<Double>()

    for (i in 0 until n) {

        val item = list.get(i)
        arr.add(item)

    }

    arr
}