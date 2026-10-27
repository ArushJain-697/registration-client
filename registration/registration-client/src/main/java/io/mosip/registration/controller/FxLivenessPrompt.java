package io.mosip.registration.controller;

import org.springframework.stereotype.Component;

import io.mosip.registration.service.liveness.LivenessPrompt;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Small always-on-top window that shows the liveness guidance during face login and packet authentication.
 * Every call returns at once; the window itself is touched only on the FX thread.
 */
@Component
public class FxLivenessPrompt implements LivenessPrompt {

	private final Label label = new Label();
	private Stage stage; // FX thread only

	@Override
	public void show(String text) {
		Platform.runLater(() -> {
			if (stage == null) {
				stage = new Stage(StageStyle.UTILITY);
				stage.setAlwaysOnTop(true);
				stage.setScene(new Scene(new StackPane(label), 420, 80));
				stage.show();
			}
			label.setText(text);
		});
	}

	@Override
	public void close() {
		Platform.runLater(() -> {
			if (stage != null) {
				stage.close();
				stage = null;
			}
		});
	}
}
