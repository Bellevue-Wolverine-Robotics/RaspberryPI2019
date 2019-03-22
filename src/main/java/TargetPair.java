import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;

public class TargetPair {

    private Target leftTarget;
    private Target rightTarget;

    public TargetPair(Target leftTarget, Target rightTarget) {
        this.leftTarget = leftTarget;
        this.rightTarget = rightTarget;
    }
}