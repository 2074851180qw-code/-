package com.fishpet

/** 台词库：想换性格就改这里。 */
object Lines {
    val click = listOf("嗯？", "嘿嘿，痒痒的。", "摸我干嘛，我是肥鱼不是猫。", "再摸一下也行。", "今天也要摸鱼吗？")
    val wake = listOf("唔…醒了醒了。", "谁把我戳醒的！", "几点了？我还想再睡会儿。")
    val angry = listOf("别戳啦，要晕了！", "再戳我就吐泡泡了！", "本肥鱼生气了（假的）。")
    val feed = listOf("好吃！", "白米饭最香了。", "再来一碗！", "嗝～")
    val hungry = listOf("肚子在叫……", "想吃白米饭了。", "是不是该喂我了？")
    val drag = listOf("放我下来！", "哇，我在飞！", "别晃别晃！")
    val drop = listOf("落地，稳。", "吓我一跳。", "这里风景不错。")
    val idle = listOf("今天也要加油哦。", "吐个泡泡。", "摸摸鱼也挺好。", "我是蓝色大肥鱼，不是咸鱼。", "喝口水吧。")

    val play = listOf("来追我呀！", "接球接球！", "看我的！", "这球归我了！")
    val dream = listOf("zzZ……白米饭……", "嘿嘿……再来一碗……", "呼……别抢我的饭……", "zzZ……")
    val night = listOf("夜深了，该睡觉啦。", "你还不睡呀？", "熬夜要变咸鱼的。", "我困了……")
    val sleepy = listOf("好困……", "想打个盹。", "眼睛睁不开了。")
    val yawn = listOf("哈～欠……", "啊——困了。", "伸个懒腰～")
    val ouch = listOf("哎哟！", "摔疼了……", "轻点扔嘛！")

    fun pick(list: List<String>): String = list.random()
}
