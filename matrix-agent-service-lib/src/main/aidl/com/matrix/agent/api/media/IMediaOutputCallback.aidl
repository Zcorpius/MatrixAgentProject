package com.matrix.agent.api.media;
import com.matrix.agent.api.media.MediaOutputSnapshot;
oneway interface IMediaOutputCallback {
    void onChanged(in MediaOutputSnapshot snapshot);
}
