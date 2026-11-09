package com.paintstudio.drawing.tool;

import com.paintstudio.drawing.model.DrawingObject;
import com.paintstudio.editor.interaction.InputSample;

public interface DrawingTool {
  void drag(InputSample sample);

  DrawingObject preview();
}
