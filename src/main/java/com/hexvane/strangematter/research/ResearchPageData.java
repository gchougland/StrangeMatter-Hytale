package com.hexvane.strangematter.research;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public final class ResearchPageData {
    public static final BuilderCodec<ResearchPageData> CODEC = BuilderCodec.builder(ResearchPageData.class, ResearchPageData::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
            .append(new KeyedCodec<>("Value", Codec.STRING), (d, v) -> d.value = v, d -> d.value).add()
            .append(new KeyedCodec<>("SMPageNonce", Codec.STRING), (d, v) -> d.pageNonce = v, d -> d.pageNonce).add()
            .build();
    public String action;
    public String value;
    public String pageNonce;
}
