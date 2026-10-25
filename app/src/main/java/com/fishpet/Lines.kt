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

    val appVideo = listOf("又在刷视频呀？", "刷之前先说好只看一会儿哦。", "这个我也想看……")
    val appChat = listOf("在跟谁聊天呀？", "回消息别回太晚哦。", "我不偷看的，真的。")
    val appFeed = listOf("又在刷资讯啦？", "别刷到停不下来哦。", "有什么新鲜事吗？")
    val appMusic = listOf("在听歌呀，我也想听～", "好听的话也放给我听听。", "跟着节奏摆摆尾巴。")
    val appGame = listOf("开黑啦？加油！", "赢了要告诉我。", "别太上头哦～")
    val appBrowser = listOf("在查什么呀？", "上网也要注意休息眼睛。")
    val focusScold = listOf("说好专注的呢！", "不许偷偷刷，我看着呢。", "快回去，还没到时间！")

    fun pick(list: List<String>): String = list.random()
}
