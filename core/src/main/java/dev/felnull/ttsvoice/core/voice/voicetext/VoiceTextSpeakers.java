package dev.felnull.ttsvoice.core.voice.voicetext;

public enum VoiceTextSpeakers {
    SHOW("show", "男性"),
    HARUKA("haruka", "女性"),
    HIKARI("hikari", "女性"),
    TAKERU("takeru", "男性"),
    SANTA("santa", "サンタ"),
    BEAR("bear", "凶暴なクマ");
    private final String id;
    private final String name;

    VoiceTextSpeakers(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
