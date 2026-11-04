/**
 * 标注模板（Label Studio label_config）单一来源 —— BUG-PARITY-13-B 自
 * mos/platform/frontend/src/annotationTemplates.js 回搬（模板数据逐字照抄，勿改：
 * 改一处=与 Vue 原页/后端约定漂移）。
 * 所有内置模板都由平台工作台渲染；只有自定义且平台不认识的控件才回退原生画布。
 * taskForMedia 未搬：媒体批量链走 mediaTasks.buildMediaTasks（文本读正文），
 * 建项目向导侧（13-D/向导卡）需要时再随卡回搬。
 */

export type MosAnnoTemplate = {
  /** 同类型模板排序时优先（templatesFor 把 preferred 提到最前） */
  preferred?: boolean
  label: string
  /** 部分模板带固定提示语，建任务时写进 data.prompt */
  prompt?: string
  hint: string
  /** 任务 data 里的字段名（buildMediaTasks 无 fields 时的兜底键） */
  dataKey: string
  /** audio/video/image/text/doc——批量建任务的「同一类型」判定就按它分组 */
  modality: string
  config: string
}

export const TEMPLATES: Record<string, MosAnnoTemplate> = {
  text_qa: {
    label: '文本问答（平台工作台）',
    hint: '问题 + 一段人工答案，平台工作台直接标注。',
    dataKey: 'instruction',
    modality: 'text',
    config: `<View>
  <Text name="instruction" value="$instruction"/>
  <Text name="input" value="$input"/>
  <TextArea name="response" toName="instruction" editable="true"
            maxSubmissions="1" rows="8" placeholder="请输入回答"/>
</View>`,
  },
  text_read: {
    preferred: true,
    label: '文本 · 阅读 / 批注',
    hint: '从媒体库选文本文件（txt / md / csv 等），正文直接放进标注页写批注或改写。',
    dataKey: 'text',
    modality: 'text',
    config: `<View>
  <Header value="阅读原文并标注"/>
  <Text name="text" value="$text"/>
  <TextArea name="response" toName="text" rows="8" editable="true"
            placeholder="写批注 / 改写结果 / 分类结论"/>
</View>`,
  },
  text_classify_single: {
    label: '文本 · 单选分类',
    hint: '给整段文本选一个类别（单选 / radio）；选中值就是训练答案。',
    dataKey: 'text',
    modality: 'text',
    config: `<View>
  <Header value="读文本，选一个类别"/>
  <Text name="text" value="$text"/>
  <Choices name="category" toName="text" choice="single-radio">
    <Choice value="正面"/>
    <Choice value="负面"/>
    <Choice value="中性"/>
  </Choices>
</View>`,
  },
  text_classify_multi: {
    label: '文本 · 多选分类',
    hint: '给整段文本选多个类别（复选 / checkbox）；选中值会拼成答案。',
    dataKey: 'text',
    modality: 'text',
    config: `<View>
  <Header value="读文本，选所有适用的类别"/>
  <Text name="text" value="$text"/>
  <Choices name="category" toName="text" choice="multiple">
    <Choice value="问题"/>
    <Choice value="建议"/>
    <Choice value="投诉"/>
    <Choice value="其他"/>
  </Choices>
</View>`,
  },
  image_classify_single: {
    label: '图片 · 单选分类',
    hint: '给整张图片选一个类别（radio）；不用画框。',
    dataKey: 'image',
    modality: 'image',
    config: `<View>
  <Header value="看图，选一个类别"/>
  <Image name="image" value="$image"/>
  <Choices name="category" toName="image" choice="single-radio">
    <Choice value="类型 A"/>
    <Choice value="类型 B"/>
    <Choice value="其他"/>
  </Choices>
</View>`,
  },
  image_classify_multi: {
    label: '图片 · 多选分类',
    hint: '一张图可以选多个类别（checkbox）；选中值会拼成答案。',
    dataKey: 'image',
    modality: 'image',
    config: `<View>
  <Header value="看图，选所有适用的类别"/>
  <Image name="image" value="$image"/>
  <Choices name="category" toName="image" choice="multiple">
    <Choice value="人"/>
    <Choice value="车"/>
    <Choice value="建筑"/>
    <Choice value="其他"/>
  </Choices>
</View>`,
  },
  audio_transcribe: {
    preferred: true,
    label: '音频 · 整段转写',
    prompt: '听音频，写完整转写。',
    hint: '听音频，写完整文字；平台内置画布直接标注。',
    dataKey: 'audio',
    modality: 'audio',
    config: `<View>
  <Header value="听音频"/>
  <Audio name="audio" value="$audio"/>
  <Header value="写转写文字"/>
  <TextArea name="transcription" toName="audio" rows="6" editable="true" maxSubmissions="1"/>
</View>`,
  },
  audio_diarize: {
    label: '音频 · 分段 + 说话人',
    prompt: '听音频，按说话人划时间段并写该段文字。',
    hint: '按时间段标说话人并写该段文字；平台内置画布直接标注。',
    dataKey: 'audio',
    modality: 'audio',
    config: `<View>
  <Header value="听音频，按说话人划时间段"/>
  <Labels name="speaker" toName="audio" choice="multiple">
    <Label value="说话人 1" background="#1677ff"/>
    <Label value="说话人 2" background="#00a870"/>
  </Labels>
  <Audio name="audio" value="$audio"/>
  <View visibleWhen="region-selected">
    <TextArea name="transcription" toName="audio" perRegion="true" required="true" rows="3"/>
  </View>
</View>`,
  },
  audio_classify: {
    label: '音频 · 分类',
    prompt: '听音频，选择类型。',
    hint: '给整段音频选类型；平台内置画布直接标注。',
    dataKey: 'audio',
    modality: 'audio',
    config: `<View>
  <Header value="听音频"/>
  <Audio name="audio" value="$audio"/>
  <Choices name="topic" toName="audio" choice="single-radio">
    <Choice value="类型 A"/>
    <Choice value="类型 B"/>
    <Choice value="其他"/>
  </Choices>
</View>`,
  },
  audio_classify_multi: {
    label: '音频 · 多选分类',
    hint: '一段音频可选多个类别（checkbox）；选中值会拼成答案。',
    dataKey: 'audio',
    modality: 'audio',
    config: `<View>
  <Header value="听音频，选所有适用的类别"/>
  <Audio name="audio" value="$audio"/>
  <Choices name="topic" toName="audio" choice="multiple">
    <Choice value="类型 A"/>
    <Choice value="类型 B"/>
    <Choice value="其他"/>
  </Choices>
</View>`,
  },
  video_classify: {
    preferred: true,
    label: '视频 · 整段分类',
    prompt: '看视频，选择类型。',
    hint: '看完整段视频选类型；平台内置画布直接标注。',
    dataKey: 'video',
    modality: 'video',
    config: `<View>
  <Header value="看视频"/>
  <Video name="video" value="$video"/>
  <Choices name="type" toName="video" choice="single-radio">
    <Choice value="类型 A"/>
    <Choice value="类型 B"/>
    <Choice value="其他"/>
  </Choices>
</View>`,
  },
  video_classify_multi: {
    label: '视频 · 多选分类',
    hint: '一段视频可选多个类别（checkbox）；选中值会拼成答案。',
    dataKey: 'video',
    modality: 'video',
    config: `<View>
  <Header value="看视频，选所有适用的类别"/>
  <Video name="video" value="$video"/>
  <Choices name="type" toName="video" choice="multiple">
    <Choice value="类型 A"/>
    <Choice value="类型 B"/>
    <Choice value="其他"/>
  </Choices>
</View>`,
  },
  video_segment: {
    label: '视频 · 时间段事件',
    prompt: '看视频，在时间轴上标事件。',
    hint: '在时间轴上标事件起止；平台内置画布直接标注。',
    dataKey: 'video',
    modality: 'video',
    config: `<View>
  <Header value="看视频，按时间轴划事件"/>
  <TimelineLabels name="event" toName="video" timelineHeight="100">
    <Label value="事件 A" background="#1677ff"/>
    <Label value="事件 B" background="#00a870"/>
  </TimelineLabels>
  <Video name="video" value="$video" frameRate="25"/>
</View>`,
  },
  image_box: {
    preferred: true,
    label: '图片 · 框选 + 描述',
    prompt: '在图片上框选目标，并写一句描述。',
    hint: '在图片上画框并选类别；PDF 逐页转图片后也用这个模板。',
    dataKey: 'image',
    modality: 'image',
    config: `<View>
  <Header value="在图片上画框，并写一句描述"/>
  <Image name="image" value="$image"/>
  <RectangleLabels name="label" toName="image">
    <Label value="目标" background="#1677ff"/>
  </RectangleLabels>
  <TextArea name="description" toName="image" rows="3" editable="true"
            placeholder="用一句话描述这张图片 / 框选结果"/>
</View>`,
  },
  doc_hypertext: {
    label: '文档 · 阅读标注',
    prompt: '阅读文档并写批注 / 抽取字段。',
    hint: 'Word 转 HTML 后阅读并写批注/抽取字段；平台内置画布直接标注。',
    dataKey: 'doc',
    modality: 'doc',
    config: `<View>
  <Header value="阅读文档并标注"/>
  <HyperText name="doc" value="$doc"/>
  <TextArea name="answer" toName="doc" rows="5" editable="true"
            placeholder="写批注 / 抽取的字段"/>
</View>`,
  },
  video_box: {
    label: '视频 · 画面框选/跟踪',
    prompt: '看视频，画框并跟踪目标。',
    hint: '关键帧画框，自动插值；平台内置画布直接标注。',
    dataKey: 'video',
    modality: 'video',
    config: `<View>
  <Header value="看视频，画框并跟踪"/>
  <Video name="video" value="$video" frameRate="25"/>
  <VideoRectangle name="box" toName="video"/>
  <Labels name="target" toName="video">
    <Label value="目标" background="#1677ff"/>
  </Labels>
</View>`,
  },
  custom: {
    label: '自定义 label_config',
    hint: '自己写 XML；非 TextArea 配置平台内置画布直接标注。',
    dataKey: 'text',
    modality: 'text',
    config: '<View>\n  \n</View>',
  },
}

export function templatesFor(kind: string) {
  return Object.entries(TEMPLATES)
    .filter(([, t]) => t.modality === kind)
    .map(([key, t]) => ({ key, ...t }))
    .sort((a, b) => (b.preferred ? 1 : 0) - (a.preferred ? 1 : 0))
}
