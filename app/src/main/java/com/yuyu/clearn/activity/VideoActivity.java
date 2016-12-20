package com.yuyu.clearn.activity;import android.content.Context;import android.media.MediaPlayer;import android.net.Uri;import android.os.Bundle;import android.os.Environment;import android.os.Handler;import android.os.Message;import android.support.v7.app.AppCompatActivity;import android.util.Log;import android.view.KeyEvent;import android.view.View;import com.google.vr.sdk.widgets.video.VrVideoView;import com.naver.speech.clientapi.SpeechRecognitionResult;import com.yuyu.clearn.R;import com.yuyu.clearn.api.realm.UserVO;import com.yuyu.clearn.api.reognizer.AudioWriterPCM;import com.yuyu.clearn.api.reognizer.NaverRecognizer;import com.yuyu.clearn.api.retrofit.MemberVO;import com.yuyu.clearn.api.retrofit.RestInterface;import com.yuyu.clearn.custom.Constant;import java.io.IOException;import java.lang.ref.WeakReference;import java.text.SimpleDateFormat;import java.util.Date;import java.util.List;import butterknife.BindView;import butterknife.ButterKnife;import io.realm.Realm;import io.realm.RealmConfiguration;import rx.Observable;import rx.Subscriber;public class VideoActivity extends AppCompatActivity {    @BindView(R.id.video_view)    VrVideoView video_view;    private final String TAG = VideoActivity.class.getSimpleName();    private final int SEND_TIME = 2000, CONTROL_TIME = 20000;    private final int MAIN_SCREEN = 0, VIDEO_SCREEN = 1, QUIZ_SCREEN = 2;    private Realm realm;    private UserVO userVO;    private Thread thread;    private Context context;    private MediaPlayer mediaPlayer;    private VrVideoView.Options options;    private AudioWriterPCM audioWriterPCM;    private NaverRecognizer naverRecognizer;    private int status, main_flag, quiz_flag;    private long v_ctime, load_time;    private boolean isPause, quiz_finish, quiz_answer[];    @Override    public void onCreate(Bundle savedInstanceState) {        super.onCreate(savedInstanceState);        setContentView(R.layout.activity_video);        ButterKnife.bind(this);        context = this;        quiz_answer = new boolean[4];        options = new VrVideoView.Options();        options.inputFormat = VrVideoView.Options.FORMAT_DEFAULT;        options.inputType = VrVideoView.Options.TYPE_MONO;        naverRecognizer = new NaverRecognizer(context, new RecognitionHandler(this), getString(R.string.CLIENT_ID));        // 시작 시 풀 스크린 모드로 변경        video_view.setDisplayMode(VrVideoView.DisplayMode.FULLSCREEN_STEREO);        video_view.fullScreenDialog.setCancelable(false);        video_view.fullScreenDialog.setOnKeyListener((dialogInterface, i, keyEvent) -> {            // 뒤로가기 버튼이 터치 되었을 경우(컨트롤러 포함) 음성 인식 이벤트를 2초간 받음            if (keyEvent.getAction() == KeyEvent.ACTION_UP && !naverRecognizer.getSpeechRecognizer().isRunning()) {                naverRecognizer.recognize();                mediaPlayerInit(R.raw.start);                new Handler() {                    @Override                    public void handleMessage(Message msg) {                        naverRecognizer.getSpeechRecognizer().stop();                    }                }.sendEmptyMessageDelayed(0, SEND_TIME);            }            return false;        });        // 첫 로딩 시간을 고려해서 2초 대기        new Handler() {            @Override            public void handleMessage(Message msg) {                mainScreenViewFirst();            }        }.sendEmptyMessageDelayed(0, SEND_TIME);    }    @Override    public void onResume() {        super.onResume();        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION ^ View.SYSTEM_UI_FLAG_FULLSCREEN ^ View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);        naverRecognizer.getSpeechRecognizer().initialize();        video_view.resumeRendering();        video_view.seekTo(v_ctime);        if (isPause) {            video_view.pauseVideo();        } else {            video_view.playVideo();        }    }    @Override    public void onPause() {        super.onPause();        naverRecognizer.getSpeechRecognizer().release();        v_ctime = video_view.getCurrentPosition();        video_view.pauseRendering();    }    // 메인 화면 영상을 띄워주기 전 Realm DB 작업 시작    public void mainScreenViewFirst() {        Log.e(TAG, "Realm 시작");        Realm.init(context);        Observable.just(realm = Realm.getInstance(new RealmConfiguration.Builder()                .name(getString(R.string.REALM_NAME))                .build()))                .subscribe(realm1 -> {                    realm1.beginTransaction();                    Observable.just(userVO = realm1.createObject(UserVO.class, getSharedPreferences(getString(R.string.VIDEO), MODE_PRIVATE).getInt(getString(R.string.NUMBER), 0)))                            .doOnUnsubscribe(() -> {                                Log.e(TAG, "doOnUnsubscribe 시작");                                mainScreenView(main_flag = 0);                                createThread();                            })                            .subscribe(userVO -> {                                userVO.setV_num(getIntent().getIntExtra(Constant.V_NUM, -1));                                userVO.setP_token(getIntent().getStringExtra(Constant.P_TOKEN));                                userVO.setStart_time(new SimpleDateFormat(getString(R.string.DATE_TYPE)).format(new Date()));                            });                });    }    // 메인 화면 영상을 띄워줌    public void mainScreenView(int main_flag) {        Log.e(TAG, "mainScreenView 시작");        load_time = System.currentTimeMillis();        status = MAIN_SCREEN;        try {//            video_view.loadVideo(Uri.parse(RestInterface.BASE + RestInterface.RESOURCES + RestInterface.VIDEO +//                    (main_flag == 0 ? RestInterface.MAIN_SCREEN : main_flag == 1 ? RestInterface.MAIN_SCREEN_FLAG_1 : main_flag == 2 ?//                            RestInterface.MAIN_SCREEN_FLAG_2 : RestInterface.MAIN_SCREEN_FLAG_3)), options);            video_view.loadVideo(Uri.parse(RestInterface.BASE + RestInterface.RESOURCES + RestInterface.VIDEO + RestInterface.MAIN_SCREEN), options);        } catch (IOException e) {            Log.e(TAG, String.valueOf(e));        }    }    // 비디오 화면 영상을 띄워주기 전 v_num과 p_token을 request하여 그에 알맞는 정보를 response    public void videoScreenViewFirst() {        RestInterface.getRestClient()                .create(RestInterface.PostVideo.class)                .video(getString(R.string.VIDEO).toLowerCase(), getIntent().getIntExtra(Constant.V_NUM, -1), getIntent().getStringExtra(Constant.P_TOKEN))                .subscribe(new Subscriber<MemberVO>() {                    @Override                    public void onCompleted() {                    }                    @Override                    public void onError(Throwable e) {                        Log.e(TAG, String.valueOf(e));                    }                    @Override                    public void onNext(MemberVO memberVO) {                        Observable.just(memberVO.getCt_file())                                .map(text -> Uri.parse(RestInterface.BASE + RestInterface.RESOURCES + RestInterface.VIDEO + memberVO.getCt_file()))                                .doOnUnsubscribe(() -> video_view.seekTo(v_ctime = memberVO.getV_ctime()))                                .subscribe(uri -> videoScreenView(uri));                    }                });    }    // 비디오 화면 영상을 띄워줌    public void videoScreenView(Uri uri) {        load_time = System.currentTimeMillis();        status = VIDEO_SCREEN;        try {            video_view.loadVideo(uri, options);        } catch (IOException e) {            Log.e(TAG, String.valueOf(e));        }    }    public void quizScreenView(int quiz_flag) {        load_time = System.currentTimeMillis();        status = QUIZ_SCREEN;        quiz_finish = false;        try {            video_view.loadVideo(Uri.parse(RestInterface.BASE + RestInterface.RESOURCES + RestInterface.VIDEO +                    (quiz_flag == 1 ? RestInterface.QUIZ_1 : quiz_flag == 2 ? RestInterface.QUIZ_2 : quiz_flag == 3 ?                            RestInterface.QUIZ_3 : RestInterface.QUIZ_4)), options);        } catch (IOException e) {            Log.e(TAG, String.valueOf(e));        }    }    public void answerScreenView(int quiz_flag, boolean quiz_answer) {        quiz_finish = true;        quizScreenView(quiz_flag + 1);        try {            if (quiz_flag == 1 || quiz_flag == 2) {                video_view.loadVideo(Uri.parse(RestInterface.BASE + RestInterface.RESOURCES + RestInterface.VIDEO +                        (quiz_answer ? RestInterface.YES : RestInterface.NO)), options);            } else {                video_view.loadVideo(Uri.parse(RestInterface.BASE + RestInterface.RESOURCES + RestInterface.VIDEO +                        (quiz_answer ? RestInterface.NO : RestInterface.YES)), options);            }        } catch (IOException e) {            Log.e(TAG, String.valueOf(e));        }    }    // 미디어 플레이어 혹은 비디오 플레이어가 종료되면 실행되는 인터페이스인    // OnCompletionListener가 VrVideoView에 없는 관계로 직접 구현    // Thread를 돌려서 해당 동영상이 종료되면(총 재생 시간보다 현재 재생 시간이 많거나 같을 경우)    // 메인 -> 무한 반복 / 비디오 -> PostFinish 인터페이스를 사용해 v_num과 v_finish를 request    public void createThread() {        Log.e(TAG, "Thread 시작");        Runnable runnable = () -> {            while (!thread.isInterrupted()) {                v_ctime = video_view.getCurrentPosition();                if (v_ctime >= video_view.getDuration() && load_time + 2000 <= System.currentTimeMillis()) {                    switch (status) {                        case MAIN_SCREEN:                            Log.e(TAG, "Main Thread 종료");                            mainScreenView(main_flag < 3 ? main_flag + 1 : (main_flag = 1));                            break;                        case VIDEO_SCREEN:                            RestInterface.getRestClient()                                    .create(RestInterface.PostFinish.class)                                    .finish(getString(R.string.FINISH).toLowerCase(), getIntent().getIntExtra(Constant.V_NUM, -1))                                    .subscribe(new Subscriber<Void>() {                                        @Override                                        public void onCompleted() {                                        }                                        @Override                                        public void onError(Throwable e) {                                            Log.e(TAG, String.valueOf(e));                                        }                                        @Override                                        public void onNext(Void aVoid) {                                            mainScreenView(main_flag = 1);                                        }                                    });                            break;                        case QUIZ_SCREEN:                            if (quiz_finish) {                                answerScreenView(quiz_flag, quiz_answer[quiz_flag]);                            } else {                                video_view.seekTo(0);                            }                        default:                            break;                    }                }            }        };        thread = new Thread(runnable);        thread.start();    }    public void handleMessage(Message msg) {        switch (msg.what) {            case R.id.clientReady:                audioWriterPCM = new AudioWriterPCM(                        Environment.getExternalStorageDirectory().getAbsolutePath() + getString(R.string.SPEECH_TEST));                audioWriterPCM.open(getString(R.string.TEST));                break;            case R.id.audioRecording:                audioWriterPCM.write((short[]) msg.obj);                break;            case R.id.finalResult:                recognizerResult(msg);                break;            case R.id.recognitionError:                if (audioWriterPCM != null) {                    audioWriterPCM.close();                }                break;            case R.id.clientInactive:                if (audioWriterPCM != null) {                    audioWriterPCM.close();                }                break;            default:                break;        }    }    public void recognizerResult(Message msg) {        SpeechRecognitionResult speechRecognitionResult = (SpeechRecognitionResult) msg.obj;        List<String> results = speechRecognitionResult.getResults();        boolean isSpeechEvent = false;        String speechEvent = getString(R.string.NONE);        // 음성 인식 이벤트 처리        for (String result : results) {            switch (status) {                case MAIN_SCREEN:                    switch (main_flag) {                        case 1:                            if (result.contains(getString(R.string.YES))) {                                speechEvent = getString(R.string.YES);                                videoScreenViewFirst();                            }                            break;                        case 2:                            if (result.contains(getString(R.string.YES))) {                                speechEvent = getString(R.string.YES);                                quizScreenView(quiz_flag = 1);                            }                            break;                        case 3:                            if (result.contains(getString(R.string.YES))) {                                speechEvent = getString(R.string.DESTROY);                                videoDestroy(realm, userVO);                            }                            break;                        default:                            break;                    }                    if (result.contains(getString(R.string.DESTROY))) {                        speechEvent = getString(R.string.DESTROY);                        videoDestroy(realm, userVO);                    } else if (result.contains(getString(R.string.STUDY))) {                        speechEvent = getString(R.string.STUDY);                        videoScreenViewFirst();                    } else if (result.contains(getString(R.string.QUIZ))) {                        speechEvent = getString(R.string.QUIZ);                        quizScreenView(quiz_flag = 1);                    }                    break;                case VIDEO_SCREEN:                    if (result.contains(getString(R.string.DESTROY))) {                        speechEvent = getString(R.string.DESTROY);                        videoSave();                    } else if (result.contains(getString(R.string.PLAY))) {                        speechEvent = getString(R.string.PLAY);                        isPause = true;                        video_view.playVideo();                    } else if (result.contains(getString(R.string.STOP))) {                        speechEvent = getString(R.string.STOP);                        isPause = false;                        video_view.pauseVideo();                    } else if (result.contains(getString(R.string.NEXT))) {                        speechEvent = getString(R.string.NEXT);                        video_view.seekTo(v_ctime += CONTROL_TIME);                    } else if (result.contains(getString(R.string.PREV))) {                        speechEvent = getString(R.string.PREV);                        video_view.seekTo(v_ctime -= CONTROL_TIME);                    } else if (result.contains(getString(R.string.RESET))) {                        speechEvent = getString(R.string.RESET);                        video_view.seekTo(v_ctime = 0);                    }                    break;                case QUIZ_SCREEN:                    if (result.contains(getString(R.string.YES))) {                        speechEvent = getString(R.string.YES);                        quiz_finish = true;                        quiz_answer[quiz_flag] = true;                    } else if (result.contains(getString(R.string.NO))) {                        speechEvent = getString(R.string.NO);                        quiz_finish = true;                        quiz_answer[quiz_flag] = false;                    }                    break;                default:                    break;            }            if (!speechEvent.equals(getString(R.string.NONE))) {                isSpeechEvent = true;                break;            }        }        if (!isSpeechEvent) {            mediaPlayerInit(R.raw.re);        }    }    //현재 재생 시간을 PostSave 인터페이스를 사용해 v_num과 v_ctime을 request    public void videoSave() {        getSharedPreferences(getString(R.string.VIDEO), MODE_PRIVATE).edit().putInt(getString(R.string.NUMBER), getSharedPreferences(getString(R.string.VIDEO), MODE_PRIVATE).getInt(getString(R.string.NUMBER), 0) + 1).apply();        RestInterface.getRestClient()                .create(RestInterface.PostSave.class)                .save(getString(R.string.SAVE).toLowerCase(), getIntent().getIntExtra(Constant.V_NUM, -1), v_ctime)                .subscribe(new Subscriber<Void>() {                    @Override                    public void onCompleted() {                    }                    @Override                    public void onError(Throwable e) {                        Log.e(TAG, String.valueOf(e));                    }                    @Override                    public void onNext(Void aVoid) {                        mainScreenView(main_flag = 1);                    }                });    }    // Realm DB 작업 종료 후 p_token과 함께 request    // OnCompletionListener 역할을 대신 수행하는 Thread 및 어플을 종료    public void videoDestroy(Realm realm, UserVO userVO) {        Observable.just(realm, userVO)                .doOnUnsubscribe(() -> Observable.just(realm)                        .map(realm2 -> realm2.where(UserVO.class).findAll().toString())                        .subscribe(realm2 -> {                            RestInterface.getRestClient()                                    .create(RestInterface.PostResult.class)                                    .result(getString(R.string.RESULT).toLowerCase(), getIntent().getStringExtra(Constant.P_TOKEN), realm2)                                    .doOnUnsubscribe(() -> {                                        mediaPlayerInit(R.raw.destroy);                                        thread.interrupt();                                        video_view.fullScreenDialog.dismiss();                                        video_view.pauseRendering();                                        video_view.shutdown();                                        finish();                                    })                                    .subscribe(new Subscriber<Void>() {                                        @Override                                        public void onCompleted() {                                        }                                        @Override                                        public void onError(Throwable e) {                                            Log.e(TAG, String.valueOf(e));                                        }                                        @Override                                        public void onNext(Void aVoid) {                                            mainScreenView(main_flag = 1);                                        }                                    });                        }))                .subscribe(realm1 -> {                    userVO.setEnd_time(new SimpleDateFormat(getString(R.string.DATE_TYPE)).format(new Date()));                    realm.insert(userVO);                    realm.commitTransaction();                });    }    public void mediaPlayerInit(int resId) {        Observable.just(mediaPlayer)                .filter(mediaPlayer1 -> mediaPlayer1 != null)                .subscribe(mediaPlayer1 -> {                    mediaPlayer1.stop();                    mediaPlayer1.release();                });        Observable.just(mediaPlayer = MediaPlayer.create(context, resId))                .subscribe(mediaPlayer1 -> mediaPlayer1.setOnPreparedListener(MediaPlayer::start));    }    private static class RecognitionHandler extends Handler {        private final WeakReference<VideoActivity> weakReference;        RecognitionHandler(VideoActivity activity) {            weakReference = new WeakReference<>(activity);        }        @Override        public void handleMessage(Message msg) {            VideoActivity activity = weakReference.get();            if (activity != null) {                activity.handleMessage(msg);            }        }    }}