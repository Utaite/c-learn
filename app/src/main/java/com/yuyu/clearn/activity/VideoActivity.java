package com.yuyu.clearn.activity;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import com.google.vr.sdk.widgets.video.VrVideoView;
import com.naver.speech.clientapi.SpeechRecognitionResult;
import com.trello.rxlifecycle.components.support.RxAppCompatActivity;
import com.yuyu.clearn.R;
import com.yuyu.clearn.api.realm.ConnectVO;
import com.yuyu.clearn.api.realm.QuizVO;
import com.yuyu.clearn.api.reognizer.AudioWriterPCM;
import com.yuyu.clearn.api.reognizer.NaverRecognizer;
import com.yuyu.clearn.api.retrofit.MemberVO;
import com.yuyu.clearn.api.retrofit.RestInterface;
import com.yuyu.clearn.custom.Constant;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import io.realm.Realm;
import io.realm.RealmConfiguration;
import rx.Observable;
import rx.Subscriber;

public class VideoActivity extends RxAppCompatActivity {

    @BindView(R.id.video_view)
    public VrVideoView video_view;

    private final String TAG = VideoActivity.class.getSimpleName();
    private final int VIDEO_REPEAT_TIME = 0, VIDEO_LOAD_TIME = 1000, SEND_TIME = 3000, CONTROL_TIME = 20000;
    private final int MAIN_FLAG_0 = 0, MAIN_FLAG_1 = 1, MAIN_FLAG_2 = 2, MAIN_FLAG_3 = 3, MAIN_FLAG_1_TIME = 7000, MAIN_FLAG_2_TIME = 17000, MAIN_FLAG_3_TIME = 27000;
    private final int MAIN_SCREEN = 0, VIDEO_SCREEN = 1, QUIZ_SCREEN = 2, ANSWER_SCREEN = 3;
    private final int QUIZ_REPEAT_MAX = 1;
    private final String CLIENT_ID = "hgjHh11TeYg649dN5zT1", TEST = "Test", SPEECH_TEST = "/NaverSpeech" + TEST, DATE_TYPE = "yyyy-MM-dd:HH-mm-ss";
    private final String VIDEO = "VIDEO", FINISH = "FINISH", SAVE = "SAVE";
    private final String NONE = "", DESTROY = "나가기", PLAY = "시작", STOP = "멈춰", NEXT = "앞으로", PREV = "뒤로", RESET = "처음으로";
    private final String STUDY = "공부", QUIZ = "퀴즈", YES = "응", O = "오", X = "엑스", o = "O", x = "X";
    private final String CONNECT_RESULT = "CONNECTRESULT", CONNECT_NUMBER = "CONNECTNUMBER", CONNECT_REALM = "CLearnConnect.db", QUIZ_RESULT = "QUIZRESULT", QUIZ_REALM = "CLearnQuiz.db", QUIZ_NUMBER = "QUIZNUMBER";

    private Thread thread;
    private Context context;
    private MediaPlayer mediaPlayer;
    private VrVideoView.Options options;
    private AudioWriterPCM audioWriterPCM;
    private NaverRecognizer naverRecognizer;

    // 정답이 O인 퀴즈의 번호
    private int[] quizAnswerTrue = new int[]{1, 2, 3, 4};
    // 퀴즈에 대한 유저의 대답을 true, false 로 값을 순서대로 저장
    private boolean[] userAnswer;

    private int status, mainFlag, quizFlag, quizRepeat, quizCount;
    private long v_ctime, loadTime;
    private boolean isPause, isQuizFinish, isFinish;
    private String start_time;
    private StringBuffer userAnswerList, quizAnswerList, resultList;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video);
        ButterKnife.bind(this);
        context = this;
        quizCount = quizAnswerTrue.length;
        userAnswer = new boolean[quizCount + 1];
        naverRecognizer = new NaverRecognizer(context, new RecognitionHandler(this), CLIENT_ID);
        userAnswerList = new StringBuffer();
        quizAnswerList = new StringBuffer();
        resultList = new StringBuffer();
        options = new VrVideoView.Options();
        options.inputFormat = VrVideoView.Options.FORMAT_DEFAULT;
        options.inputType = VrVideoView.Options.TYPE_MONO;
        // 시작 시 풀 스크린 모드로 변경
        video_view.setDisplayMode(VrVideoView.DisplayMode.FULLSCREEN_STEREO);
        video_view.fullScreenDialog.setCancelable(false);
        Handler handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                naverRecognizer.getSpeechRecognizer().stop();
            }
        };
        video_view.fullScreenDialog.setOnKeyListener((dialogInterface, i, keyEvent) -> {
            // 뒤로가기 버튼이 터치 되었을 경우(컨트롤러 포함) 음성 인식 이벤트를 2초간 받음
            if (keyEvent.getAction() == KeyEvent.ACTION_UP && !naverRecognizer.getSpeechRecognizer().isRunning()) {
                naverRecognizer.recognize();
                mediaPlayerInit(R.raw.start);
                handler.sendEmptyMessageDelayed(0, SEND_TIME);
            }
            return false;
        });
        // 첫 로딩 시간을 고려해서 1초 대기
        new Handler() {
            @Override
            public void handleMessage(Message msg) {
                mainScreenViewFirst();
            }
        }.sendEmptyMessageDelayed(0, VIDEO_LOAD_TIME);
    }

    @Override
    public void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION ^ View.SYSTEM_UI_FLAG_FULLSCREEN ^ View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        naverRecognizer.getSpeechRecognizer().initialize();
        video_view.resumeRendering();
        video_view.seekTo(v_ctime);
        if (isPause) {
            video_view.pauseVideo();
        } else {
            video_view.playVideo();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        naverRecognizer.getSpeechRecognizer().release();
        v_ctime = video_view.getCurrentPosition();
        video_view.pauseRendering();
    }

    // 메인 화면 영상을 띄워주기 전 Realm DB 작업 시작
    public void mainScreenViewFirst() {
        Realm.init(context);
        start_time = new SimpleDateFormat(DATE_TYPE).format(new Date());
        mainScreenView(mainFlag = MAIN_FLAG_0);
        createThread();
    }

    // 메인 화면 영상을 띄워줌
    public void mainScreenView(int mainFlag) {
        loadTime = System.currentTimeMillis();
        status = MAIN_SCREEN;
        try {
            video_view.loadVideo(Uri.parse(RestInterface.BASE + RestInterface.RESOURCES + RestInterface.VIDEO + RestInterface.MAIN_SCREEN), options);
        } catch (IOException e) {
            Log.e(TAG, String.valueOf(e));
        }
        mainScreenSeek(mainFlag);
    }

    // 메인 화면 영상 시크 조절
    public void mainScreenSeek(int mainFlag) {
        loadTime = System.currentTimeMillis();
        status = MAIN_SCREEN;
        video_view.seekTo(mainFlag == MAIN_FLAG_0 ? VIDEO_REPEAT_TIME : mainFlag == MAIN_FLAG_1 ? MAIN_FLAG_1_TIME : mainFlag == MAIN_FLAG_2 ? MAIN_FLAG_2_TIME : MAIN_FLAG_3_TIME);
    }

    // 비디오 화면 영상을 띄워주기 전 v_num과 p_token을 request하여 그에 알맞는 정보를 response
    public void videoScreenViewFirst() {
        RestInterface.getRestClient()
                .create(RestInterface.PostVideo.class)
                .video(VIDEO.toLowerCase(), getIntent().getIntExtra(Constant.V_NUM, -1), getIntent().getStringExtra(Constant.P_TOKEN))
                .subscribe(new Subscriber<MemberVO>() {
                    @Override
                    public void onCompleted() {
                    }

                    @Override
                    public void onError(Throwable e) {
                        Log.e(TAG, String.valueOf(e));
                    }

                    @Override
                    public void onNext(MemberVO memberVO) {
                        Observable.just(memberVO.getCh_file())
                                .compose(bindToLifecycle())
                                .map(text -> Uri.parse(RestInterface.BASE + RestInterface.RESOURCES + RestInterface.VIDEO + memberVO.getCh_file()))
                                .doOnUnsubscribe(() -> video_view.seekTo(v_ctime = memberVO.getV_ctime()))
                                .subscribe(uri -> videoScreenView(uri));
                    }
                });
    }

    // 비디오 화면 영상을 띄워줌
    public void videoScreenView(Uri uri) {
        loadTime = System.currentTimeMillis();
        status = VIDEO_SCREEN;
        try {
            video_view.loadVideo(uri, options);
        } catch (IOException e) {
            Log.e(TAG, String.valueOf(e));
        }
    }

    //  퀴즈 화면 영상을 띄워줌
    public void quizScreenView(int quizFlag) {
        isQuizFinish = false;
        loadTime = System.currentTimeMillis();
        status = QUIZ_SCREEN;
        try {
            video_view.loadVideo(Uri.parse(RestInterface.BASE + RestInterface.RESOURCES + RestInterface.VIDEO +
                    RestInterface.QUIZ_ + String.valueOf(quizFlag) + RestInterface.MP4), options);
        } catch (IOException e) {
            Log.e(TAG, String.valueOf(e));
        }
        video_view.seekTo(VIDEO_REPEAT_TIME);
    }

    // 대답 화면 영상을 띄워줌
    public void answerScreenView(int quizFlag, boolean userAnswer) {
        quizRepeat = 0;
        loadTime = System.currentTimeMillis();
        status = ANSWER_SCREEN;
        try {
            /* answer true / userAnswer true = YES
            answer true / userAnswer false = NO
            answer false / userAnswer true = NO
            answer false / userAnswer false = YES */
            video_view.loadVideo(Uri.parse(RestInterface.BASE + RestInterface.RESOURCES + RestInterface.VIDEO +
                    (answerIsTrue(quizAnswerTrue, quizFlag) == userAnswer ? RestInterface.YES : RestInterface.NO)), options);
        } catch (IOException e) {
            Log.e(TAG, String.valueOf(e));
        }
        video_view.seekTo(VIDEO_REPEAT_TIME);
        userAnswerList.append(userAnswer ? o : x);
        quizAnswerList.append(answerIsTrue(quizAnswerTrue, quizFlag) ? o : x);
        resultList.append(userAnswer == answerIsTrue(quizAnswerTrue, quizFlag) ? o : x);
    }

    public boolean answerIsTrue(int[] quizAnswerTrue, int quizFlag) {
        for (int answerTrue : quizAnswerTrue) {
            if (quizFlag == answerTrue) {
                return true;
            }
        }
        return false;
    }

    public void createThread() {
        Runnable runnable = () -> {
            while (!thread.isInterrupted()) {
                v_ctime = video_view.getCurrentPosition();

                // v_ctime에 따라 mainFlag 값 지속적으로 변경
                if (status == MAIN_SCREEN) {
                    mainFlag = v_ctime < MAIN_FLAG_1_TIME ? MAIN_FLAG_0 : v_ctime < MAIN_FLAG_2_TIME ? MAIN_FLAG_1 : v_ctime < MAIN_FLAG_3_TIME ? MAIN_FLAG_2 : MAIN_FLAG_3;
                }

                // 미디어 플레이어 혹은 비디오 플레이어가 종료되면 실행되는 인터페이스인
                // OnCompletionListener가 VrVideoView에 없는 관계로 직접 구현
                // Thread를 돌려서 해당 동영상이 종료되면(총 재생 시간보다 현재 재생 시간이 많거나 같을 경우)
                // 메인 -> 반복
                // 비디오 -> PostFinish 인터페이스를 사용해 v_num과 v_finish를 request
                // 퀴즈 -> 대답 영상 띄워줌
                // 대답 -> 퀴즈 or 메인 영상 띄워줌
                if (v_ctime >= video_view.getDuration() && loadTime + 1000 <= System.currentTimeMillis() && !isFinish) {
                    switch (status) {

                        case MAIN_SCREEN:
                            mainScreenSeek(mainFlag < 3 ? ++mainFlag : (mainFlag = MAIN_FLAG_1));
                            break;

                        case VIDEO_SCREEN:
                            isFinish = true;
                            RestInterface.getRestClient()
                                    .create(RestInterface.PostFinish.class)
                                    .finish(FINISH.toLowerCase(), getIntent().getIntExtra(Constant.V_NUM, -1))
                                    .subscribe(new Subscriber<Void>() {
                                        @Override
                                        public void onCompleted() {
                                        }

                                        @Override
                                        public void onError(Throwable e) {
                                            Log.e(TAG, String.valueOf(e));
                                        }

                                        @Override
                                        public void onNext(Void aVoid) {
                                            mainScreenView(mainFlag = MAIN_FLAG_1);
                                            isFinish = false;
                                        }
                                    });
                            break;

                        case QUIZ_SCREEN:
                            if (isQuizFinish) {
                                answerScreenView(quizFlag, userAnswer[quizFlag]);
                            } else if (quizRepeat < QUIZ_REPEAT_MAX) {
                                ++quizRepeat;
                                video_view.seekTo(VIDEO_REPEAT_TIME);
                            }
                            break;

                        case ANSWER_SCREEN:
                            if (quizFlag < quizCount) {
                                quizScreenView(++quizFlag);
                            } else {
                                isFinish = true;
                                quizSave(mainFlag = MAIN_FLAG_1);
                            }
                            break;

                        default:
                            break;
                    }

                }
            }
        };
        thread = new Thread(runnable);
        thread.start();
    }

    public void handleMessage(Message msg) {
        switch (msg.what) {

            case R.id.clientReady:
                audioWriterPCM = new AudioWriterPCM(
                        Environment.getExternalStorageDirectory().getAbsolutePath() + SPEECH_TEST);
                audioWriterPCM.open(TEST);
                break;

            case R.id.audioRecording:
                audioWriterPCM.write((short[]) msg.obj);
                break;

            case R.id.finalResult:
                recognizerResult(msg);
                break;

            case R.id.recognitionError:
                if (audioWriterPCM != null) {
                    audioWriterPCM.close();
                }
                break;

            case R.id.clientInactive:
                if (audioWriterPCM != null) {
                    audioWriterPCM.close();
                }
                break;

            default:
                break;
        }
    }

    public void recognizerResult(Message msg) {
        SpeechRecognitionResult speechRecognitionResult = (SpeechRecognitionResult) msg.obj;
        List<String> results = speechRecognitionResult.getResults();
        boolean isSpeechEvent = false;
        // 음성 인식 이벤트 처리
        for (String result : results) {
            if (!getSpeech(status, mainFlag, result).equals(NONE)) {
                isSpeechEvent = true;
                break;
            }
        }
        if (!isSpeechEvent) {
            mediaPlayerInit(R.raw.re);
        }
    }

    //현재 재생 시간을 PostSave 인터페이스를 사용해 v_num과 v_ctime을 request
    public void videoSave() {
        RestInterface.getRestClient()
                .create(RestInterface.PostSave.class)
                .save(SAVE.toLowerCase(), getIntent().getIntExtra(Constant.V_NUM, -1), v_ctime)
                .subscribe(new Subscriber<Void>() {
                    @Override
                    public void onCompleted() {
                    }

                    @Override
                    public void onError(Throwable e) {
                        Log.e(TAG, String.valueOf(e));
                    }

                    @Override
                    public void onNext(Void aVoid) {
                        mainScreenView(mainFlag = MAIN_FLAG_1);
                    }
                });
    }

    // Realm DB에 List들을 대입 후 저장
    public void quizSave(int mainFlag) {
        Observable.just(Realm.getInstance(new RealmConfiguration.Builder()
                .name(QUIZ_REALM)
                .build()))
                .compose(bindToLifecycle())
                .subscribe(realm -> {
                    realm.beginTransaction();
                    Observable.just(realm.createObject(QuizVO.class, getSharedPreferences(VIDEO, MODE_PRIVATE).getInt(QUIZ_NUMBER, 0)))
                            .compose(bindToLifecycle())
                            .doOnUnsubscribe(() -> Observable.just(realm)
                                    .map(realm1 -> realm1.where(QuizVO.class).findAll().toString())
                                    .subscribe(realm2 -> {
                                        RestInterface.getRestClient()
                                                .create(RestInterface.PostQuizResult.class)
                                                .quizResult(QUIZ_RESULT.toLowerCase(), getIntent().getStringExtra(Constant.P_TOKEN), realm2)
                                                .compose(bindToLifecycle())
                                                .subscribe(new Subscriber<Void>() {
                                                    @Override
                                                    public void onCompleted() {
                                                    }

                                                    @Override
                                                    public void onError(Throwable e) {
                                                        Log.e(TAG, String.valueOf(e));
                                                    }

                                                    @Override
                                                    public void onNext(Void aVoid) {
                                                        mainScreenView(mainFlag);
                                                        isFinish = false;
                                                    }
                                                });
                                    }))
                            .subscribe(quizVO -> {
                                getSharedPreferences(VIDEO, MODE_PRIVATE).edit().putInt(QUIZ_NUMBER, getSharedPreferences(VIDEO, MODE_PRIVATE).getInt(QUIZ_NUMBER, 0) + 1).apply();
                                quizVO.setV_num(getIntent().getIntExtra(Constant.V_NUM, -1));
                                quizVO.setUserAnswerList(userAnswerList.toString());
                                quizVO.setQuizAnswerList(quizAnswerList.toString());
                                quizVO.setResultList(resultList.toString());
                                userAnswerList.setLength(0);
                                quizAnswerList.setLength(0);
                                resultList.setLength(0);
                                realm.insert(quizVO);
                                realm.commitTransaction();
                            });
                });
    }

    // Realm DB 작업 종료 후 p_token과 함께 request
    // OnCompletionListener 역할을 대신 수행하는 Thread 및 어플을 종료
    public void videoDestroy() {
        Observable.just(Realm.getInstance(new RealmConfiguration.Builder()
                .name(CONNECT_REALM)
                .build()))
                .compose(bindToLifecycle())
                .subscribe(realm -> {
                    realm.beginTransaction();
                    Observable.just(realm.createObject(ConnectVO.class, getSharedPreferences(VIDEO, MODE_PRIVATE).getInt(CONNECT_NUMBER, 0)))
                            .compose(bindToLifecycle())
                            .doOnUnsubscribe(() -> Observable.just(realm)
                                    .compose(bindToLifecycle())
                                    .map(realm1 -> realm1.where(ConnectVO.class).findAll().toString())
                                    .subscribe(realm2 -> {
                                        RestInterface.getRestClient()
                                                .create(RestInterface.PostConnectResult.class)
                                                .connectResult(CONNECT_RESULT.toLowerCase(), getIntent().getStringExtra(Constant.P_TOKEN), realm2)
                                                .compose(bindToLifecycle())
                                                .subscribe(new Subscriber<Void>() {
                                                    @Override
                                                    public void onCompleted() {
                                                    }

                                                    @Override
                                                    public void onError(Throwable e) {
                                                        Log.e(TAG, String.valueOf(e));
                                                    }

                                                    @Override
                                                    public void onNext(Void aVoid) {
                                                        mediaPlayerInit(R.raw.destroy);
                                                        thread.interrupt();
                                                        video_view.fullScreenDialog.dismiss();
                                                        video_view.pauseRendering();
                                                        video_view.shutdown();
                                                        finish();
                                                    }
                                                });
                                    }))
                            .subscribe(connectVO -> {
                                getSharedPreferences(VIDEO, MODE_PRIVATE).edit().putInt(CONNECT_NUMBER, getSharedPreferences(VIDEO, MODE_PRIVATE).getInt(CONNECT_NUMBER, 0) + 1).apply();
                                connectVO.setV_num(getIntent().getIntExtra(Constant.V_NUM, -1));
                                connectVO.setStart_time(start_time);
                                connectVO.setEnd_time(new SimpleDateFormat(DATE_TYPE).format(new Date()));
                                realm.insert(connectVO);
                                realm.commitTransaction();
                            });
                });
    }

    public void mediaPlayerInit(int resId) {
        Observable.just(mediaPlayer)
                .compose(bindToLifecycle())
                .filter(mediaPlayer1 -> mediaPlayer1 != null)
                .subscribe(mediaPlayer1 -> {
                    mediaPlayer1.stop();
                    mediaPlayer1.release();
                });
        Observable.just(mediaPlayer = MediaPlayer.create(context, resId))
                .compose(bindToLifecycle())
                .subscribe(mediaPlayer1 -> mediaPlayer1.setOnPreparedListener(MediaPlayer::start));
    }

    private static class RecognitionHandler extends Handler {

        private final WeakReference<VideoActivity> weakReference;

        RecognitionHandler(VideoActivity activity) {
            weakReference = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            VideoActivity activity = weakReference.get();

            if (activity != null) {
                activity.handleMessage(msg);
            }
        }
    }

    public String getSpeech(int status, int mainFlag, String result) {
        switch (status) {

            case MAIN_SCREEN:

                if (mainFlag == MAIN_FLAG_1) {
                    if (result.contains(YES)) {
                        videoScreenViewFirst();
                        return STUDY;
                    }

                } else if (mainFlag == MAIN_FLAG_2) {
                    if (result.contains(YES)) {
                        quizScreenView(quizFlag = 1);
                        return QUIZ;
                    }

                } else if (mainFlag == MAIN_FLAG_3) {
                    if (result.contains(YES)) {
                        videoDestroy();
                        return DESTROY;
                    }
                }

                if (result.contains(DESTROY)) {
                    videoDestroy();
                    return DESTROY;

                } else if (result.contains(STUDY)) {
                    videoScreenViewFirst();
                    return STUDY;

                } else if (result.contains(QUIZ)) {
                    quizScreenView(quizFlag = 1);
                    return QUIZ;
                }
                break;

            case VIDEO_SCREEN:

                if (result.contains(DESTROY)) {
                    videoSave();
                    return DESTROY;

                } else if (result.contains(PLAY)) {
                    isPause = true;
                    video_view.playVideo();
                    return PLAY;

                } else if (result.contains(STOP)) {
                    isPause = false;
                    video_view.pauseVideo();
                    return STOP;

                } else if (result.contains(NEXT)) {
                    video_view.seekTo(v_ctime += CONTROL_TIME);
                    return NEXT;

                } else if (result.contains(PREV)) {
                    video_view.seekTo(v_ctime -= CONTROL_TIME);
                    return PREV;

                } else if (result.contains(RESET)) {
                    video_view.seekTo(v_ctime = VIDEO_REPEAT_TIME);
                    return RESET;
                }
                break;

            case QUIZ_SCREEN:

                if (result.contains(O)) {
                    userAnswer[quizFlag] = true;
                    isQuizFinish = true;
                    return O;

                } else if (result.contains(X)) {
                    userAnswer[quizFlag] = false;
                    isQuizFinish = true;
                    return X;

                } else if (result.contains(DESTROY)) {
                    mainScreenView(mainFlag = MAIN_FLAG_1);
                    return DESTROY;
                }
                break;

            default:
                break;
        }
        return NONE;
    }

}