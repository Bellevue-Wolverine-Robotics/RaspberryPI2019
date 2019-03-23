public class TargetPair {

    private Target leftTarget;
    private Target rightTarget;

    public TargetPair(Target leftTarget, Target rightTarget) {
        this.leftTarget = leftTarget;
        this.rightTarget = rightTarget;
    }

    public int getCenterTargetPair() {
        return (leftTarget.getCenterTargetX() + rightTarget.getCenterTargetX())  / 2;
    }

    public double getWidth() {
        return rightTarget.getCenterTargetX() - leftTarget.getCenterTargetX();
    }
}