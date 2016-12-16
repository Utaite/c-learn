package com.yuyu.clearn.fragment;

import android.app.Fragment;
import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.vr.sdk.widgets.video.VrVideoView;
import com.naver.speech.clientapi.SpeechRecognitionResult;
import com.yuyu.clearn.R;
import com.yuyu.clearn.activity.LoginActivity;
import com.yuyu.clearn.api.AudioWriterPCM;
import com.yuyu.clearn.api.NaverRecognizer;
import com.yuyu.clearn.retrofit.Member;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.POST;

public class VideoFragment extends Fragment {

    // 이전 로그인 액티비티에서 전달받은 값 v_num과 p_token을 서버에 request 이후
    // 일치하는 데이터의 여러 정보를 response 받음
    public interface PostVideo {
        @FormUrlEncoded
        @POST("api/video")
        Call<Member> video(@Field("v_num") int v_num,
                           @Field("p_token") String p_token);
    }

    // 동영상 시청이 끝났을 경우 v_num을 request하여
    // 해당 v_num을 가진 동영상의 v_finish를 Y로 update함
    public interface PostFinish {
        @FormUrlEncoded
        @POST("api/finish")
        Call<Void> finish(@Field("v_num") int v_num);
    }

    // 동영상 시청이 중단되었을 경우 v_num과 v_ctime을 request하여
    // 해당 v_num을 가진 동영상의 v_ctime을 update함
    public interface PostSave {
        @FormUrlEncoded
        @POST("api/save")
        Call<Void> save(@Field("v_num") int v_num,
                        @Field("v_ctime") long v_ctime);
    }

    @BindView(R.id.video_view)
    VrVideoView video_view;

    private static final String TAG = VideoFragment.class.getSimpleName();
    private static final String CLIENT_ID = "hgjHh11TeYg649dN5zT1";
    private static final int NONE = 0, DESTROY = 1, PLAY = 2, STOP = 3, NEXT = 4, PREV = 5, RESET = 6;

    private RecognitionHandler handler;
    private NaverRecognizer naverRecognizer;
    private AudioWriterPCM writer;
    private VrVideoView.Options options;
    private Context context;
    private MediaPlayer mediaPlayer;
    private Thread pThread;

    private int v_num, event;
    private boolean isPaused, isEvent, isFinish;
    private long v_ctime, loadTime;
    // 음성 인식 이벤트 과정

    public void handleMessage(Message msg) {
        switch (msg.what) {
            case R.id.clientReady:
                writer = new AudioWriterPCM(
                        Environment.getExternalStorageDirectory().getAbsolutePath() + "/NaverSpeechTest");
                writer.open("Test");
                break;

            case R.id.audioRecording:
                writer.write((short[]) msg.obj);
                break;

            case R.id.finalResult:
                SpeechRecognitionResult speechRecognitionResult = (SpeechRecognitionResult) msg.obj;
                List<String> results = speechRecognitionResult.getResults();
                for (String result : results) {
                    // 음성 인식 이벤트 처리
                    event = NONE;
                    if (result.contains("종료")) {
                        videoDestroy(true);
                        event = DESTROY;
                    } else if (result.contains("재생")) {
                        isPaused = true;
                        video_view.playVideo();
                        event = PLAY;
                    } else if (result.contains("정지")) {
                        isPaused = false;
                        video_view.pauseVideo();
                        event = STOP;
                    } else if (result.contains("앞으로")) {
                        video_view.seekTo(v_ctime += 20000);
                        event = NEXT;
                    } else if (result.contains("뒤로")) {
                        video_view.seekTo(v_ctime -= 20000);
                        event = PREV;
                    } else if (result.contains("처음으로")) {
                        video_view.seekTo(v_ctime = 0);
                        event = RESET;
                    }
                    if (event != NONE) {
                        event = NONE;
                        isEvent = true;
                        break;
                    }
                }
                if (!isEvent) {
                    mediaPlayerInit(R.raw.re);
                } else {
                    isEvent = false;
                }

            case R.id.recognitionError:
                if (writer != null) {
                    writer.close();
                }
                break;

            case R.id.clientInactive:
                if (writer != null) {
                    writer.close();
                }
                break;
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment, container, false);
        ButterKnife.bind(this, view);
        context = getActivity();
        handler = new RecognitionHandler(this);
        naverRecognizer = new NaverRecognizer(context, handler, CLIENT_ID);
        v_num = getActivity().getIntent().getIntExtra("v_num", -1);
        options = new VrVideoView.Options();
        options.inputFormat = VrVideoView.Options.FORMAT_DEFAULT;
        options.inputType = VrVideoView.Options.TYPE_MONO;
        // 동영상 시작 시 풀 스크린 모드로 진행
        video_view.setDisplayMode(VrVideoView.DisplayMode.FULLSCREEN_STEREO);
        video_view.fullScreenDialog.setCancelable(false);
        video_view.fullScreenDialog.setOnKeyListener((dialogInterface, i, keyEvent) -> {
            if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
                // 뒤로가기 버튼이 터치 되었을 경우(컨트롤러 포함) 음성 인식 이벤트를 2초간 받음
                if (!naverRecognizer.getSpeechRecognizer().isRunning()) {
                    naverRecognizer.recognize();
                    mediaPlayerInit(R.raw.start);
                    new Handler() {
                        @Override
                        public void handleMessage(Message msg) {
                            naverRecognizer.getSpeechRecognizer().stop();
                        }
                    }.sendEmptyMessageDelayed(0, 2000);
                }
            }
            return false;
        });
        // PostVideo 인터페이스를 사용해 이전 로그인 액티비티에서 전달받은 값
        // v_num과 p_token을 서버에 request 이후 response 받은 여러 정보들을 사용
        Call<Member> videoCall = new Retrofit.Builder()
                .baseUrl(LoginActivity.BASE + "/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(PostVideo.class)
                .video(v_num, getActivity().getIntent().getStringExtra("p_token"));
        videoCall.enqueue(new Callback<Member>() {
                              @Override
                              public void onResponse(Call<Member> call, Response<Member> response) {
                                  Member repo = response.body();
                                  v_ctime = repo.getV_ctime();
                                  try {
                                      video_view.loadVideo(Uri.parse(LoginActivity.BASE + "/resources/" + repo.getCt_file()), options);
                                  } catch (IOException e) {
                                      Log.e(TAG, String.valueOf(e));
                                  }
                                  video_view.seekTo(repo.getV_ctime());
                                  // 미디어 플레이어 혹은 비디오 플레이어가 종료되면 실행되는 인터페이스인
                                  // OnCompletionListener가 VrVideoView에 없는 관계로 직접 구현함
                                  // Thread를 돌려서 해당 동영상이 종료되면(총 재생 시간보다 현재 재생 시간이 많거나 같을 경우)
                                  // PostFinish 인터페이스를 사용해 이전 로그인 액티비티에서 전달받은 값 v_num을 서버에 request
                                  // 이후 해당 v_num의 v_finish의 값을 N에서 Y로 update
                                  loadTime = System.currentTimeMillis();
                                  Runnable runnable = () -> {
                                      while (!pThread.isInterrupted()) {
                                          v_ctime = video_view.getCurrentPosition();
                                          if (v_ctime >= video_view.getDuration() && loadTime + 2000 <= System.currentTimeMillis() && !isFinish) {
                                              Log.e(v_ctime + "//", video_view.getDuration() + "//");
                                              isFinish = true;
                                              Call<Void> finishCall = new Retrofit.Builder()
                                                      .baseUrl(LoginActivity.BASE + "/")
                                                      .addConverterFactory(GsonConverterFactory.create())
                                                      .build()
                                                      .create(PostFinish.class)
                                                      .finish(v_num);
                                              finishCall.enqueue(new Callback<Void>() {
                                                  @Override
                                                  public void onResponse(Call<Void> call, Response<Void> response) {
                                                      videoDestroy(false);
                                                  }

                                                  @Override
                                                  public void onFailure(Call<Void> call, Throwable t) {
                                                      Log.e(TAG, String.valueOf(t));
                                                  }
                                              });
                                          }
                                      }
                                  };
                                  pThread = new Thread(runnable);
                                  pThread.start();
                              }

                              @Override
                              public void onFailure(Call<Member> call, Throwable t) {
                                  Log.e(TAG, String.valueOf(t));
                              }
                          }
        );
        return view;
    }

    @Override
    public void onPause() {
        super.onPause();
        naverRecognizer.getSpeechRecognizer().release();
        video_view.pauseRendering();
        v_ctime = video_view.getCurrentPosition();
    }

    @Override
    public void onResume() {
        super.onResume();
        int uiOptions = getActivity().getWindow().getDecorView().getSystemUiVisibility();
        uiOptions ^= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        uiOptions ^= View.SYSTEM_UI_FLAG_FULLSCREEN;
        uiOptions ^= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        getActivity().getWindow().getDecorView().setSystemUiVisibility(uiOptions);
        naverRecognizer.getSpeechRecognizer().initialize();
        video_view.resumeRendering();
        video_view.seekTo(v_ctime);
        if (isPaused) {
            video_view.pauseVideo();
        } else {
            video_view.playVideo();
        }
    }

    // OnCompletionListener 역할을 대신 수행하는 Thread를 종료하고,
    // 현재 재생 시간을 PostSave 인터페이스를 사용해 이전 로그인 액티비티에서 전달받은 값
    // v_num과 현재 재생 시간인 v_ctime을 서버에 request
    // 이후 해당 v_num에 v_ctime을 update 후 어플을 종료
    public void videoDestroy(boolean shutdown) {
        if (shutdown) {
            mediaPlayerInit(R.raw.destroy);
        }
        pThread.interrupt();
        Call<Void> saveCall = new Retrofit.Builder()
                .baseUrl(LoginActivity.BASE + "/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(PostSave.class)
                .save(v_num, v_ctime);
        saveCall.enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (shutdown) {
                    video_view.pauseRendering();
                    video_view.shutdown();
                    getActivity().finish();
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Log.e(TAG, String.valueOf(t));
            }
        });
    }

    public void mediaPlayerInit(int resId) {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
        }
        mediaPlayer = MediaPlayer.create(context, resId);
        mediaPlayer.setOnPreparedListener(mp -> mp.start());
    }

    private static class RecognitionHandler extends Handler {
        private final WeakReference<VideoFragment> mActivity;

        RecognitionHandler(VideoFragment activity) {
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            VideoFragment activity = mActivity.get();
            if (activity != null) {
                activity.handleMessage(msg);
            }
        }
    }
}